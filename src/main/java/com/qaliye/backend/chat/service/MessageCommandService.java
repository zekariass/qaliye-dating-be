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
import com.qaliye.backend.billing.repository.ActionLimitRepository;
import com.qaliye.backend.billing.repository.MessagePairTrackerRepository;
import com.qaliye.backend.billing.service.ActionCostService;
import com.qaliye.backend.billing.service.CreditService;
import com.qaliye.backend.discovery.exception.ActionLimitExceededException;
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
    private final ActionCostService actionCostService;
    private final ActionLimitRepository actionLimitRepo;
    private final CreditService creditService;
    private final MessagePairTrackerRepository pairTrackerRepo;

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
                                  ActionCostService actionCostService,
                                  ActionLimitRepository actionLimitRepo,
                                  CreditService creditService,
                                  MessagePairTrackerRepository pairTrackerRepo) {
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
        this.actionCostService = actionCostService;
        this.actionLimitRepo = actionLimitRepo;
        this.creditService = creditService;
        this.pairTrackerRepo = pairTrackerRepo;
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

        // Step 5: Evaluate and consume MESSAGE action cost (per-recipient, idempotent via clientMessageId)
        UUID otherUserId = match.otherUserId(callerId);
        consumeMessageActionCost(callerId, otherUserId, req.getClientMessageId());

        // Steps 7-8: Reserve and increment sequence
        long sequenceNumber = matchRepository.reserveAndIncrementSequence(matchId);

        // Step 9: Insert message (created_at = clock_timestamp() in SQL)
        ChatMessageRepository.MessageRow inserted = messageRepository.insert(
                matchId, callerId, req.getClientMessageId(),
                req.getMessageType(), trimmedBody, sequenceNumber);

        OffsetDateTime occurredAt = inserted.createdAt();

        // Steps 11-12: Insert Realtime outbox events
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

        // Step 4b-5: Validate and classify files, then evaluate combined action cost
        UUID otherUserIdForCost = match.otherUserId(callerId);
        List<ValidatedAttachment> validated = validateAndClassifyFiles(safeFiles, durations);
        long voiceCount = validated.stream().filter(v -> "VOICE".equals(v.attachmentType())).count();
        long imageCount = validated.stream().filter(v -> "IMAGE".equals(v.attachmentType())).count();
        consumeAttachmentMessageActionCost(callerId, otherUserIdForCost, req.getClientMessageId(), voiceCount, imageCount);

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

        OffsetDateTime occurredAt = inserted.createdAt();

        // Steps 11-12: Insert Realtime outbox events with attachment metadata
        outboxService.createMessageCreatedEvent(matchId, inserted.id(), sequenceNumber,
                callerId, req.getMessageType(), trimmedBody, occurredAt, realtimeAttachments);
        outboxService.createInboxMatchUpdatedEvent(matchId, callerId, occurredAt);
        outboxService.createInboxMatchUpdatedEvent(matchId, otherUserIdForCost, occurredAt);

        // Step 13: Insert push notification outbox event
        notificationOutboxService.createChatMessageEvent(
                inserted.id(), matchId, callerId, otherUserIdForCost, occurredAt);

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

    /**
     * Enforces the MESSAGE cost for a single text message and charges credits if required.
     * Routes to LIFETIME per-pair tracking or existing per-user period tracking based on period_type.
     */
    private void consumeMessageActionCost(UUID callerId, UUID recipientId, UUID clientMessageId) {
        long creditCost = enforceActionCost(callerId, recipientId, "MESSAGE", 1);
        if (creditCost > 0) {
            creditService.consumeCredits(callerId, creditCost, "MESSAGE", "msg-" + clientMessageId);
        }
    }

    /**
     * Enforces costs for a message with attachments: VOICE_MESSAGE and/or IMAGE_MESSAGE.
     * Each action type has its own independent per-recipient tracking.
     * MESSAGE is not enforced for attachment messages — it is only enforced for
     * plain text messages in {@link #consumeMessageActionCost}.
     * Charges the single highest credit cost across all applicable attachment action types.
     */
    private void consumeAttachmentMessageActionCost(UUID callerId, UUID recipientId,
                                                     UUID clientMessageId, long voiceCount, long imageCount) {
        String idemKey = "msg-" + clientMessageId;

        long creditCharge = 0;
        String creditActionType = "MESSAGE";

        if (voiceCount > 0) {
            long voiceUnit = enforceActionCost(callerId, recipientId, "VOICE_MESSAGE", (int) voiceCount);
            long voiceCharge = voiceUnit * voiceCount;
            if (voiceCharge > creditCharge) { creditCharge = voiceCharge; creditActionType = "VOICE_MESSAGE"; }
        }

        if (imageCount > 0) {
            long imageUnit = enforceActionCost(callerId, recipientId, "IMAGE_MESSAGE", (int) imageCount);
            long imageCharge = imageUnit * imageCount;
            if (imageCharge > creditCharge) { creditCharge = imageCharge; creditActionType = "IMAGE_MESSAGE"; }
        }

        if (creditCharge > 0) {
            creditService.consumeCredits(callerId, creditCharge, creditActionType, idemKey);
        }
    }

    /**
     * Resolves and enforces the action cost for a single action code against a recipient.
     * - If period_type = LIFETIME: uses per-sender-recipient pair tracking.
     * - Otherwise: delegates to the existing per-user period tracker (DAY/MONTH/BILLING_CYCLE).
     *
     * @return the credit cost per unit to charge (0 if within free allowance)
     */
    private long enforceActionCost(UUID callerId, UUID recipientId, String actionCode, int count) {
        ActionCostService.PlanRuleConfig config = actionCostService.getPlanRuleConfig(callerId, actionCode);

        if (config.ruleId() == null) {
            return config.memberCreditCost();
        }

        if ("LIFETIME".equals(config.periodType())) {
            return enforceLifetimePairLimit(callerId, recipientId, config, actionCode, count);
        } else {
            return enforcePerUserPeriodLimit(callerId, actionCode, count);
        }
    }

    /**
     * Enforces a LIFETIME per-pair limit. Ensures the tracker row exists, atomically
     * increments if within the limit, or charges credits if over the limit.
     *
     * @return credit cost per unit (0 if within free allowance)
     */
    private long enforceLifetimePairLimit(UUID callerId, UUID recipientId,
                                           ActionCostService.PlanRuleConfig config,
                                           String actionCode, int count) {
        if (config.limitValue() == null) {
            return config.memberCreditCost();
        }
        pairTrackerRepo.ensureLifetimePairExists(callerId, recipientId, config.ruleId());
        boolean withinLimit = pairTrackerRepo
                .tryIncrementLifetimeByUnderLimit(callerId, recipientId, config.ruleId(),
                        config.limitValue(), count)
                .isPresent();
        if (withinLimit) return 0L;

        if (!config.applyCreditAfterLimit()) {
            throw new ActionLimitExceededException(actionCode, "LIFETIME");
        }
        pairTrackerRepo.incrementLifetimeBy(callerId, recipientId, config.ruleId(), count);
        return config.actualCreditCost();
    }

    /**
     * Enforces a per-user period-based limit (DAY / MONTH / BILLING_CYCLE) using the
     * existing user_action_limits_tracker table — unchanged from the original behaviour.
     *
     * @return credit cost per unit (0 if within free allowance)
     */
    private long enforcePerUserPeriodLimit(UUID callerId, String actionCode, int count) {
        ActionCostService.ActionCostResult cost = actionCostService.evaluate(callerId, actionCode);
        if (cost.isBlocked()) {
            throw new ActionLimitExceededException(actionCode, cost.periodType());
        }
        if (cost.ruleId() != null && cost.limitValue() != null) {
            actionLimitRepo.ensureExists(callerId, cost.ruleId(), cost.periodStart(), cost.periodEnd());
            boolean ok = count <= 1
                    ? actionLimitRepo.tryIncrementUnderLimit(callerId, cost.ruleId(),
                            cost.periodStart(), cost.limitValue()).isPresent()
                    : actionLimitRepo.tryIncrementByUnderLimit(callerId, cost.ruleId(),
                            cost.periodStart(), cost.limitValue(), count).isPresent();
            if (!ok && !cost.requiresCredits()) {
                throw new ActionLimitExceededException(actionCode, cost.periodType());
            }
        }
        return cost.requiresCredits() ? cost.creditCost() : 0L;
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
