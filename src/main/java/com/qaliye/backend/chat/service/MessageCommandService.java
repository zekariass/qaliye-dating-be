package com.qaliye.backend.chat.service;

import com.qaliye.backend.chat.config.ChatProperties;
import com.qaliye.backend.chat.dto.ChatAttachmentDto;
import com.qaliye.backend.chat.dto.ChatMessageDto;
import com.qaliye.backend.chat.dto.SendMessageRequest;
import com.qaliye.backend.chat.exception.IdempotencyConflictException;
import com.qaliye.backend.chat.exception.InvalidMessageException;
import com.qaliye.backend.chat.repository.ChatAttachmentRepository;
import com.qaliye.backend.chat.repository.ChatAttachmentRepository.AttachmentRow;
import com.qaliye.backend.chat.repository.ChatMatchRepository;
import com.qaliye.backend.chat.repository.ChatMessageRepository;
import com.qaliye.backend.discovery.dto.UserPlanEntitlement;
import com.qaliye.backend.discovery.exception.DailyLimitExceededException;
import com.qaliye.backend.discovery.repository.DailyLimitRepository;
import com.qaliye.backend.discovery.service.PlanEntitlementService;
import com.qaliye.backend.notifications.service.NotificationOutboxService;
import com.qaliye.backend.storage.SupabaseStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class MessageCommandService {

    private static final Logger log = LoggerFactory.getLogger(MessageCommandService.class);
    private static final Set<String> ALLOWED_TYPES = Set.of("TEXT", "ICEBREAKER", "PROMPT_REPLY");
    private static final int MAX_BODY_LENGTH = 2000;

    private final ChatMatchRepository matchRepository;
    private final ChatMessageRepository messageRepository;
    private final MatchAuthorizationService authorizationService;
    private final ChatOutboxService outboxService;
    private final ChatRateLimitService rateLimitService;
    private final ChatDtoMapper mapper;
    private final NotificationOutboxService notificationOutboxService;
    private final ChatAttachmentRepository attachmentRepository;
    private final SupabaseStorageService storageService;
    private final ChatProperties chatProps;
    private final PlanEntitlementService entitlementService;
    private final DailyLimitRepository dailyLimitRepo;

    public MessageCommandService(ChatMatchRepository matchRepository,
                                  ChatMessageRepository messageRepository,
                                  MatchAuthorizationService authorizationService,
                                  ChatOutboxService outboxService,
                                  ChatRateLimitService rateLimitService,
                                  ChatDtoMapper mapper,
                                  NotificationOutboxService notificationOutboxService,
                                  ChatAttachmentRepository attachmentRepository,
                                  SupabaseStorageService storageService,
                                  ChatProperties chatProps,
                                  PlanEntitlementService entitlementService,
                                  DailyLimitRepository dailyLimitRepo) {
        this.matchRepository = matchRepository;
        this.messageRepository = messageRepository;
        this.authorizationService = authorizationService;
        this.outboxService = outboxService;
        this.rateLimitService = rateLimitService;
        this.mapper = mapper;
        this.notificationOutboxService = notificationOutboxService;
        this.attachmentRepository = attachmentRepository;
        this.storageService = storageService;
        this.chatProps = chatProps;
        this.entitlementService = entitlementService;
        this.dailyLimitRepo = dailyLimitRepo;
    }

    public record SendResult(ChatMessageDto message, boolean isNew) {}

    @Transactional
    public SendResult sendMessage(UUID callerId, UUID matchId, SendMessageRequest req) {
        validateRequest(req);

        String trimmedBody = req.getBody().trim();

        // Step 1: Check idempotency key (no lock)
        Optional<ChatMessageRepository.MessageRow> existing =
                messageRepository.findByIdempotencyKey(callerId, req.getClientMessageId());
        if (existing.isPresent()) {
            return handleExistingMessage(existing.get(), matchId, req.getMessageType(), trimmedBody);
        }

        rateLimitService.checkSendMessage(callerId, matchId);

        // Step 3: Lock match row
        ChatMatchRepository.MatchRow match = matchRepository.findByIdForUpdate(matchId)
                .orElseThrow(com.qaliye.backend.chat.exception.MatchNotFoundException::new);
        if (!match.isParticipant(callerId))
            throw new com.qaliye.backend.chat.exception.MatchAccessDeniedException();
        if (!"ACTIVE".equals(match.status()))
            throw new com.qaliye.backend.chat.exception.MatchNotActiveException();
        authorizationService.checkNoActiveBlock(match.userOneId(), match.userTwoId());

        // Step 4: Re-check idempotency after lock
        existing = messageRepository.findByIdempotencyKey(callerId, req.getClientMessageId());
        if (existing.isPresent()) {
            return handleExistingMessage(existing.get(), matchId, req.getMessageType(), trimmedBody);
        }

        // Steps 7-8: Reserve and increment sequence
        long sequenceNumber = matchRepository.reserveAndIncrementSequence(matchId);

        // Step 9: Insert message (created_at = clock_timestamp() in SQL)
        ChatMessageRepository.MessageRow inserted = messageRepository.insert(
                matchId, callerId, req.getClientMessageId(),
                req.getMessageType(), trimmedBody, sequenceNumber);

        OffsetDateTime occurredAt = inserted.createdAt();

        // Steps 11-12: Insert Realtime outbox events
        UUID otherUserId = match.otherUserId(callerId);
        outboxService.createMessageCreatedEvent(matchId, inserted.id(), sequenceNumber,
                callerId, req.getMessageType(), trimmedBody, occurredAt);
        outboxService.createInboxMatchUpdatedEvent(matchId, callerId, occurredAt);
        outboxService.createInboxMatchUpdatedEvent(matchId, otherUserId, occurredAt);

        // Step 13: Insert push notification outbox event (same transaction, idempotent)
        notificationOutboxService.createChatMessageEvent(
                inserted.id(), matchId, callerId, otherUserId, occurredAt);

        boolean isUserOne = match.isUserOne(callerId);
        ChatMessageDto dto = mapper.toMessageDto(
                inserted,
                isUserOne ? match.userTwoLastReadSequence() : match.userOneLastReadSequence(),
                isUserOne ? match.userTwoLastDeliveredSequence() : match.userOneLastDeliveredSequence(),
                callerId);

        return new SendResult(dto, true);
    }

    @Transactional
    public SendResult sendMessageWithAttachments(UUID callerId, UUID matchId,
                                                  SendMessageRequest req,
                                                  List<MultipartFile> files,
                                                  List<Long> durations) {
        validateRequestWithAttachments(req, files);

        String trimmedBody = req.getBody() != null ? req.getBody().trim() : null;
        if (trimmedBody != null && trimmedBody.isEmpty()) trimmedBody = null;

        List<MultipartFile> safeFiles = files != null ? files : List.of();

        // Step 1: Check idempotency key (no lock)
        Optional<ChatMessageRepository.MessageRow> existing =
                messageRepository.findByIdempotencyKey(callerId, req.getClientMessageId());
        if (existing.isPresent()) {
            return handleExistingMessageWithAttachments(existing.get(), matchId, req.getMessageType(), trimmedBody);
        }

        rateLimitService.checkSendMessage(callerId, matchId);

        // Step 3: Lock match row
        ChatMatchRepository.MatchRow match = matchRepository.findByIdForUpdate(matchId)
                .orElseThrow(com.qaliye.backend.chat.exception.MatchNotFoundException::new);
        if (!match.isParticipant(callerId))
            throw new com.qaliye.backend.chat.exception.MatchAccessDeniedException();
        if (!"ACTIVE".equals(match.status()))
            throw new com.qaliye.backend.chat.exception.MatchNotActiveException();
        authorizationService.checkNoActiveBlock(match.userOneId(), match.userTwoId());

        // Step 4: Re-check idempotency after lock
        existing = messageRepository.findByIdempotencyKey(callerId, req.getClientMessageId());
        if (existing.isPresent()) {
            return handleExistingMessageWithAttachments(existing.get(), matchId, req.getMessageType(), trimmedBody);
        }

        // Step 5: Validate and classify files
        List<ValidatedAttachment> validated = validateAndClassifyFiles(safeFiles, durations);

        // Step 5b: Enforce daily voice/image message limits
        long voiceCount = validated.stream().filter(v -> "VOICE".equals(v.attachmentType())).count();
        long imageCount = validated.stream().filter(v -> "IMAGE".equals(v.attachmentType())).count();
        if (voiceCount > 0 || imageCount > 0) {
            UserPlanEntitlement ent = entitlementService.loadEntitlement(callerId);
            dailyLimitRepo.ensureRowExists(callerId);
            DailyLimitRepository.DailyLimitRow limits = dailyLimitRepo.lockForUpdate(callerId)
                    .orElseThrow(() -> new IllegalStateException("Daily limits row missing after upsert"));

            if (ent.dailyVoiceChatMsgLimit() != null
                    && limits.voiceChatMsgsUsed() + voiceCount > ent.dailyVoiceChatMsgLimit()) {
                throw new DailyLimitExceededException("VOICE_CHAT_MSGS");
            }
            if (ent.dailyImageChatMsgLimit() != null
                    && limits.imageChatMsgsUsed() + imageCount > ent.dailyImageChatMsgLimit()) {
                throw new DailyLimitExceededException("IMAGE_CHAT_MSGS");
            }
        }

        // Steps 7-8: Reserve and increment sequence
        long sequenceNumber = matchRepository.reserveAndIncrementSequence(matchId);

        // Step 9: Insert message
        ChatMessageRepository.MessageRow inserted = messageRepository.insert(
                matchId, callerId, req.getClientMessageId(),
                req.getMessageType(), trimmedBody, sequenceNumber);

        // Step 10: Upload files and insert attachment records
        List<AttachmentRow> attachmentRows = new ArrayList<>();
        List<ChatOutboxService.AttachmentMetadata> realtimeAttachments = new ArrayList<>();
        String bucket = chatProps.getAttachment().getBucket();

        try {
            for (ValidatedAttachment va : validated) {
                String storagePath = generateStoragePath(matchId, inserted.id(), va.fileName);
                storageService.uploadFile(bucket, storagePath, va.bytes, va.contentType);

                AttachmentRow attRow = attachmentRepository.insert(
                        inserted.id(), va.attachmentType, va.fileName,
                        va.contentType, va.fileSize, bucket, storagePath, va.durationMs);
                attachmentRows.add(attRow);

                realtimeAttachments.add(new ChatOutboxService.AttachmentMetadata(
                        attRow.id(), attRow.attachmentType(), attRow.fileName(),
                        attRow.contentType(), attRow.fileSizeBytes(),
                        attRow.durationMs(), attRow.createdAt()));
            }
        } catch (Exception e) {
            log.error("Failed to upload attachments for message {}: {}", inserted.id(), e.getMessage(), e);
            for (AttachmentRow ar : attachmentRows) {
                storageService.deleteObject(ar.storageBucket(), ar.storagePath());
            }
            throw new InvalidMessageException("Failed to upload one or more attachments.");
        }

        // Step 10b: Increment daily voice/image message counters
        if (voiceCount > 0) {
            dailyLimitRepo.incrementVoiceChatMsgs(callerId, (int) voiceCount);
        }
        if (imageCount > 0) {
            dailyLimitRepo.incrementImageChatMsgs(callerId, (int) imageCount);
        }

        OffsetDateTime occurredAt = inserted.createdAt();

        // Steps 11-12: Insert Realtime outbox events with attachment metadata
        UUID otherUserId = match.otherUserId(callerId);
        outboxService.createMessageCreatedEvent(matchId, inserted.id(), sequenceNumber,
                callerId, req.getMessageType(), trimmedBody, occurredAt, realtimeAttachments);
        outboxService.createInboxMatchUpdatedEvent(matchId, callerId, occurredAt);
        outboxService.createInboxMatchUpdatedEvent(matchId, otherUserId, occurredAt);

        // Step 13: Insert push notification outbox event
        notificationOutboxService.createChatMessageEvent(
                inserted.id(), matchId, callerId, otherUserId, occurredAt);

        boolean isUserOne = match.isUserOne(callerId);
        ChatMessageDto dto = mapper.toMessageDto(
                inserted,
                isUserOne ? match.userTwoLastReadSequence() : match.userOneLastReadSequence(),
                isUserOne ? match.userTwoLastDeliveredSequence() : match.userOneLastDeliveredSequence(),
                callerId, attachmentRows);

        return new SendResult(dto, true);
    }

    private record ValidatedAttachment(
            String attachmentType, String fileName, String contentType,
            long fileSize, byte[] bytes, Long durationMs
    ) {}

    private List<ValidatedAttachment> validateAndClassifyFiles(List<MultipartFile> files, List<Long> durations) {
        ChatProperties.Attachment cfg = chatProps.getAttachment();
        Set<String> allowedImage = Set.copyOf(cfg.getAllowedImageContentTypes());
        Set<String> allowedVoice = Set.copyOf(cfg.getAllowedVoiceContentTypes());

        int imageCount = 0, voiceCount = 0;
        List<ValidatedAttachment> result = new ArrayList<>();

        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            String ct = file.getContentType();
            String name = file.getOriginalFilename() != null ? file.getOriginalFilename() : "file";

            if (ct == null) {
                throw new InvalidMessageException("Content type is missing for file: " + name);
            }

            boolean isImage = allowedImage.contains(ct);
            boolean isVoice = allowedVoice.contains(ct);

            if (!isImage && !isVoice) {
                throw new InvalidMessageException("Content type not allowed: " + ct);
            }

            if (isImage) {
                imageCount++;
                if (imageCount > cfg.getMaxImageAttachments()) {
                    throw new InvalidMessageException(
                            "Too many image attachments. Maximum: " + cfg.getMaxImageAttachments());
                }
                if (file.getSize() > cfg.getImageMaxFileSizeBytes()) {
                    throw new InvalidMessageException("Image file exceeds maximum size: " + name);
                }
            }

            if (isVoice) {
                voiceCount++;
                if (voiceCount > cfg.getMaxVoiceAttachments()) {
                    throw new InvalidMessageException(
                            "Too many voice attachments. Maximum: " + cfg.getMaxVoiceAttachments());
                }
                if (file.getSize() > cfg.getVoiceMaxFileSizeBytes()) {
                    throw new InvalidMessageException("Voice file exceeds maximum size: " + name);
                }
                Long durationMs = (durations != null && i < durations.size()) ? durations.get(i) : null;
                if (durationMs == null || durationMs <= 0) {
                    throw new InvalidMessageException("Voice attachment requires a positive duration_ms");
                }
                if (durationMs > cfg.getVoiceMaxDurationSeconds() * 1000L) {
                    throw new InvalidMessageException(
                            "Voice duration exceeds maximum of " + cfg.getVoiceMaxDurationSeconds() + " seconds");
                }
                try {
                    result.add(new ValidatedAttachment("VOICE", name, ct, file.getSize(), file.getBytes(), durationMs));
                } catch (IOException e) {
                    throw new InvalidMessageException("Failed to read voice file: " + name);
                }
            } else {
                try {
                    result.add(new ValidatedAttachment("IMAGE", name, ct, file.getSize(), file.getBytes(), null));
                } catch (IOException e) {
                    throw new InvalidMessageException("Failed to read image file: " + name);
                }
            }
        }

        if (result.size() > cfg.getMaxTotalAttachments()) {
            throw new InvalidMessageException(
                    "Too many attachments. Maximum: " + cfg.getMaxTotalAttachments());
        }

        return result;
    }

    private String generateStoragePath(UUID matchId, UUID messageId, String fileName) {
        String sanitized = fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (sanitized.length() > 200) sanitized = sanitized.substring(0, 200);
        return matchId + "/" + messageId + "/" + UUID.randomUUID() + "/" + sanitized;
    }

    private SendResult handleExistingMessageWithAttachments(ChatMessageRepository.MessageRow existing,
                                                             UUID matchId,
                                                             String requestedType, String requestedBody) {
        if (!existing.matchId().equals(matchId)
                || !existing.messageType().equals(requestedType)
                || !Objects.equals(existing.body(), requestedBody)) {
            throw new IdempotencyConflictException();
        }
        List<AttachmentRow> attachments = attachmentRepository
                .findByMessageIds(List.of(existing.id())).getOrDefault(existing.id(), List.of());
        ChatMessageDto dto = mapper.toMessageDto(existing, 0, 0, existing.senderUserId(), attachments);
        return new SendResult(dto, false);
    }

    private void validateRequestWithAttachments(SendMessageRequest req, List<MultipartFile> files) {
        if (req.getClientMessageId() == null) {
            throw new InvalidMessageException("clientMessageId is required.");
        }
        if (req.getMessageType() == null || !ALLOWED_TYPES.contains(req.getMessageType())) {
            throw new InvalidMessageException(
                    "messageType must be one of: TEXT, ICEBREAKER, PROMPT_REPLY.");
        }
        boolean hasBody = req.getBody() != null && !req.getBody().trim().isEmpty();
        boolean hasFiles = files != null && !files.isEmpty();
        if (!hasBody && !hasFiles) {
            throw new InvalidMessageException("Message must contain a body or at least one attachment.");
        }
        if (hasBody) {
            String trimmed = req.getBody().trim();
            if (trimmed.codePointCount(0, trimmed.length()) > MAX_BODY_LENGTH) {
                throw new InvalidMessageException("body must be at most 2000 characters.");
            }
            if (trimmed.chars().allMatch(c -> c < 32 || c == 127)) {
                throw new InvalidMessageException("body contains only control characters.");
            }
        }
    }

    private SendResult handleExistingMessage(ChatMessageRepository.MessageRow existing, UUID matchId,
                                              String requestedType, String requestedBody) {
        if (!existing.matchId().equals(matchId)
                || !existing.messageType().equals(requestedType)
                || !existing.body().equals(requestedBody)) {
            throw new IdempotencyConflictException();
        }
        ChatMessageDto dto = mapper.toMessageDto(existing, 0, 0, existing.senderUserId());
        return new SendResult(dto, false);
    }

    @Transactional
    public void clearConversation(UUID callerId, UUID matchId) {
        MatchAuthorizationService.MatchContext ctx = authorizationService.authorize(callerId, matchId);
        long latestSequence = ctx.nextMessageSequence() - 1;
        if (latestSequence < 1) return;
        matchRepository.updateClearedSequence(matchId, ctx.isUserOne(), latestSequence);
    }

    private void validateRequest(SendMessageRequest req) {
        if (req.getClientMessageId() == null) {
            throw new InvalidMessageException("clientMessageId is required.");
        }
        if (req.getMessageType() == null || !ALLOWED_TYPES.contains(req.getMessageType())) {
            throw new InvalidMessageException(
                    "messageType must be one of: TEXT, ICEBREAKER, PROMPT_REPLY.");
        }
        if (req.getBody() == null || req.getBody().trim().isEmpty()) {
            throw new InvalidMessageException("body must not be blank.");
        }
        String trimmed = req.getBody().trim();
        if (trimmed.codePointCount(0, trimmed.length()) > MAX_BODY_LENGTH) {
            throw new InvalidMessageException("body must be at most 2000 characters.");
        }
        if (trimmed.chars().allMatch(c -> c < 32 || c == 127)) {
            throw new InvalidMessageException("body contains only control characters.");
        }
    }
}
