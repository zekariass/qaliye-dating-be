package com.qaliye.backend.chat.service;

import com.qaliye.backend.chat.dto.ChatMessageDto;
import com.qaliye.backend.chat.dto.SendMessageRequest;
import com.qaliye.backend.chat.exception.IdempotencyConflictException;
import com.qaliye.backend.chat.exception.InvalidMessageException;
import com.qaliye.backend.chat.repository.ChatMatchRepository;
import com.qaliye.backend.chat.repository.ChatMessageRepository;
import com.qaliye.backend.notifications.service.NotificationOutboxService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class MessageCommandService {

    private static final Set<String> ALLOWED_TYPES = Set.of("TEXT", "ICEBREAKER", "PROMPT_REPLY");
    private static final int MAX_BODY_LENGTH = 2000;

    private final ChatMatchRepository matchRepository;
    private final ChatMessageRepository messageRepository;
    private final MatchAuthorizationService authorizationService;
    private final ChatOutboxService outboxService;
    private final ChatRateLimitService rateLimitService;
    private final ChatDtoMapper mapper;
    private final NotificationOutboxService notificationOutboxService;

    public MessageCommandService(ChatMatchRepository matchRepository,
                                  ChatMessageRepository messageRepository,
                                  MatchAuthorizationService authorizationService,
                                  ChatOutboxService outboxService,
                                  ChatRateLimitService rateLimitService,
                                  ChatDtoMapper mapper,
                                  NotificationOutboxService notificationOutboxService) {
        this.matchRepository = matchRepository;
        this.messageRepository = messageRepository;
        this.authorizationService = authorizationService;
        this.outboxService = outboxService;
        this.rateLimitService = rateLimitService;
        this.mapper = mapper;
        this.notificationOutboxService = notificationOutboxService;
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
