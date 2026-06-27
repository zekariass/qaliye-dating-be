package com.qaliye.backend.chat;

import com.qaliye.backend.chat.dto.ChatMessageDto;
import com.qaliye.backend.chat.dto.SendMessageRequest;
import com.qaliye.backend.chat.exception.*;
import com.qaliye.backend.chat.repository.ChatMatchRepository;
import com.qaliye.backend.chat.repository.ChatMessageRepository;
import com.qaliye.backend.chat.service.*;
import com.qaliye.backend.notifications.service.NotificationOutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageCommandServiceTest {

    @Mock ChatMatchRepository matchRepository;
    @Mock ChatMessageRepository messageRepository;
    @Mock MatchAuthorizationService authorizationService;
    @Mock ChatOutboxService outboxService;
    @Mock ChatRateLimitService rateLimitService;  // interface — implementation swappable
    @Mock ChatDtoMapper mapper;
    @Mock NotificationOutboxService notificationOutboxService;

    MessageCommandService service;

    UUID callerId   = UUID.randomUUID();
    UUID matchId    = UUID.randomUUID();
    UUID otherUser  = UUID.randomUUID();
    UUID clientMsgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new MessageCommandService(
                matchRepository, messageRepository, authorizationService,
                outboxService, rateLimitService, mapper, notificationOutboxService);
    }

    @Test
    void sendMessage_newMessage_returns201Result() {
        MatchRow match = buildActiveMatch();
        MessageRow inserted = buildMessageRow(1L);
        ChatMessageDto dto = buildDto(inserted);

        when(messageRepository.findByIdempotencyKey(callerId, clientMsgId)).thenReturn(Optional.empty());
        when(matchRepository.findByIdForUpdate(matchId)).thenReturn(Optional.of(match));
        when(matchRepository.reserveAndIncrementSequence(matchId)).thenReturn(1L);
        when(messageRepository.insert(any(), any(), any(), anyString(), anyString(), anyLong()))
                .thenReturn(inserted);
        when(mapper.toMessageDto(any(), anyLong(), anyLong(), any())).thenReturn(dto);

        SendMessageRequest req = buildRequest("TEXT", "Hello world");
        MessageCommandService.SendResult result = service.sendMessage(callerId, matchId, req);

        assertThat(result.isNew()).isTrue();
        assertThat(result.message()).isSameAs(dto);
        verify(outboxService).createMessageCreatedEvent(any(), any(), anyLong(), any(), anyString(), anyString(), any());
        verify(outboxService, times(2)).createInboxMatchUpdatedEvent(any(), any(), any());
        verify(notificationOutboxService).createChatMessageEvent(any(), eq(matchId), eq(callerId), eq(otherUser), any());
    }

    @Test
    void sendMessage_idempotentRetry_sameContent_returns200Result() {
        MessageRow existing = buildMessageRow(1L);
        ChatMessageDto dto = buildDto(existing);

        when(messageRepository.findByIdempotencyKey(callerId, clientMsgId))
                .thenReturn(Optional.of(existing));
        when(mapper.toMessageDto(any(), anyLong(), anyLong(), any())).thenReturn(dto);

        SendMessageRequest req = buildRequest("TEXT", "Hello world");
        MessageCommandService.SendResult result = service.sendMessage(callerId, matchId, req);

        assertThat(result.isNew()).isFalse();
        verifyNoInteractions(matchRepository, outboxService);
        // Rate limiter must NOT be charged for idempotent retries
        verifyNoInteractions(rateLimitService);
        // Notification outbox must NOT be created for idempotent retries
        verifyNoInteractions(notificationOutboxService);
    }

    @Test
    void sendMessage_idempotentRetry_differentContent_throwsConflict() {
        MessageRow existing = buildMessageRow(1L);
        when(messageRepository.findByIdempotencyKey(callerId, clientMsgId))
                .thenReturn(Optional.of(existing));

        SendMessageRequest req = buildRequest("TEXT", "Different content");
        assertThatThrownBy(() -> service.sendMessage(callerId, matchId, req))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void sendMessage_matchNotActive_throwsMatchNotActive() {
        MatchRow endedMatch = buildEndedMatch();
        when(messageRepository.findByIdempotencyKey(callerId, clientMsgId)).thenReturn(Optional.empty());
        when(matchRepository.findByIdForUpdate(matchId)).thenReturn(Optional.of(endedMatch));

        SendMessageRequest req = buildRequest("TEXT", "Hello world");
        assertThatThrownBy(() -> service.sendMessage(callerId, matchId, req))
                .isInstanceOf(MatchNotActiveException.class);
    }

    @Test
    void sendMessage_matchNotFound_throwsMatchNotFound() {
        when(messageRepository.findByIdempotencyKey(callerId, clientMsgId)).thenReturn(Optional.empty());
        when(matchRepository.findByIdForUpdate(matchId)).thenReturn(Optional.empty());

        SendMessageRequest req = buildRequest("TEXT", "Hello world");
        assertThatThrownBy(() -> service.sendMessage(callerId, matchId, req))
                .isInstanceOf(MatchNotFoundException.class);
    }

    @Test
    void sendMessage_notParticipant_throwsAccessDenied() {
        ChatMatchRepository.MatchRow matchWithOthers = new ChatMatchRepository.MatchRow(
                matchId, UUID.randomUUID(), UUID.randomUUID(), "ACTIVE",
                null, null, null, 1L, 0L, 0L, 0L, 0L, null, null, null, null, null, null);
        when(messageRepository.findByIdempotencyKey(callerId, clientMsgId)).thenReturn(Optional.empty());
        when(matchRepository.findByIdForUpdate(matchId)).thenReturn(Optional.of(matchWithOthers));

        SendMessageRequest req = buildRequest("TEXT", "Hello world");
        assertThatThrownBy(() -> service.sendMessage(callerId, matchId, req))
                .isInstanceOf(MatchAccessDeniedException.class);
    }

    @Test
    void sendMessage_blankBody_throwsInvalidMessage() {
        SendMessageRequest req = buildRequest("TEXT", "   ");
        assertThatThrownBy(() -> service.sendMessage(callerId, matchId, req))
                .isInstanceOf(InvalidMessageException.class);
    }

    @Test
    void sendMessage_nullBody_throwsInvalidMessage() {
        SendMessageRequest req = new SendMessageRequest();
        req.setClientMessageId(clientMsgId);
        req.setMessageType("TEXT");
        req.setBody(null);
        assertThatThrownBy(() -> service.sendMessage(callerId, matchId, req))
                .isInstanceOf(InvalidMessageException.class);
    }

    @Test
    void sendMessage_invalidType_throwsInvalidMessage() {
        SendMessageRequest req = buildRequest("IMAGE", "not-allowed.jpg");
        assertThatThrownBy(() -> service.sendMessage(callerId, matchId, req))
                .isInstanceOf(InvalidMessageException.class);
    }

    @Test
    void sendMessage_bodyTooLong_throwsInvalidMessage() {
        String longBody = "a".repeat(2001);
        SendMessageRequest req = buildRequest("TEXT", longBody);
        assertThatThrownBy(() -> service.sendMessage(callerId, matchId, req))
                .isInstanceOf(InvalidMessageException.class);
    }

    private ChatMatchRepository.MatchRow buildActiveMatch() {
        return new ChatMatchRepository.MatchRow(matchId, callerId, otherUser, "ACTIVE",
                null, null, null, 2L, 0L, 0L, 0L, 0L, null, null, null, null, null, null);
    }

    private ChatMatchRepository.MatchRow buildEndedMatch() {
        return new ChatMatchRepository.MatchRow(matchId, callerId, otherUser, "ENDED",
                null, null, "USER_UNMATCH", 1L, 0L, 0L, 0L, 0L, null, null, null, null, null, null);
    }

    private ChatMessageRepository.MessageRow buildMessageRow(long seq) {
        return new ChatMessageRepository.MessageRow(UUID.randomUUID(), matchId, seq, callerId,
                "TEXT", "Hello world", "APPROVED", OffsetDateTime.now(), null);
    }

    private ChatMessageDto buildDto(ChatMessageRepository.MessageRow row) {
        return new ChatMessageDto(row.id(), row.matchId(), row.sequenceNumber(),
                row.senderUserId(), row.messageType(), row.body(), "SENT", row.createdAt().toInstant());
    }

    private SendMessageRequest buildRequest(String type, String body) {
        SendMessageRequest req = new SendMessageRequest();
        req.setClientMessageId(clientMsgId);
        req.setMessageType(type);
        req.setBody(body);
        return req;
    }
}
