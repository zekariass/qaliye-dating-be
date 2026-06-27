package com.qaliye.backend.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaliye.backend.notifications.repository.NotificationOutboxRepository;
import com.qaliye.backend.notifications.service.NotificationOutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationOutboxServiceTest {

    @Mock NotificationOutboxRepository outboxRepo;

    NotificationOutboxService service;

    UUID messageId   = UUID.randomUUID();
    UUID matchId     = UUID.randomUUID();
    UUID senderId    = UUID.randomUUID();
    UUID recipientId = UUID.randomUUID();
    UUID campaignId  = UUID.randomUUID();
    UUID actionId    = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new NotificationOutboxService(outboxRepo, new ObjectMapper());
    }

    @Test
    void createChatMessageEvent_insertsWithCorrectType() {
        when(outboxRepo.existsByDedupeKey(anyString())).thenReturn(false);

        service.createChatMessageEvent(messageId, matchId, senderId, recipientId, OffsetDateTime.now());

        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxRepo).insert(any(), typeCaptor.capture(),
                eq(recipientId), eq(senderId), eq(matchId), eq(messageId),
                isNull(), isNull(), anyString(), anyString(), anyString(), any(), any());

        assertThat(typeCaptor.getValue()).isEqualTo("CHAT_MESSAGE");
    }

    @Test
    void createChatMessageEvent_whenDedupeKeyExists_doesNotInsert() {
        when(outboxRepo.existsByDedupeKey(anyString())).thenReturn(true);

        service.createChatMessageEvent(messageId, matchId, senderId, recipientId, OffsetDateTime.now());

        verify(outboxRepo, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
    }

    @Test
    void createChatMessageEvent_dedupeKey_includesMessageAndRecipient() {
        when(outboxRepo.existsByDedupeKey(anyString())).thenReturn(false);

        service.createChatMessageEvent(messageId, matchId, senderId, recipientId, OffsetDateTime.now());

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxRepo).insert(any(), any(), any(), any(), any(), any(), any(), any(),
                keyCaptor.capture(), any(), any(), any(), any());

        assertThat(keyCaptor.getValue())
                .contains(messageId.toString())
                .contains(recipientId.toString());
    }

    @Test
    void createChatMessageEvent_collapseKey_isMatchBased() {
        when(outboxRepo.existsByDedupeKey(anyString())).thenReturn(false);

        service.createChatMessageEvent(messageId, matchId, senderId, recipientId, OffsetDateTime.now());

        ArgumentCaptor<String> collapseCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxRepo).insert(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), collapseCaptor.capture(), any(), any(), any());

        assertThat(collapseCaptor.getValue()).contains(matchId.toString());
    }

    @Test
    void createChatMessageEvent_expiresAt_isSetToFuture() {
        when(outboxRepo.existsByDedupeKey(anyString())).thenReturn(false);
        OffsetDateTime now = OffsetDateTime.now();

        service.createChatMessageEvent(messageId, matchId, senderId, recipientId, now);

        ArgumentCaptor<OffsetDateTime> expiresCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(outboxRepo).insert(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), expiresCaptor.capture(), any());

        assertThat(expiresCaptor.getValue()).isAfter(now);
    }

    @Test
    void createMatchCreatedEvent_insertsWithCorrectType() {
        when(outboxRepo.existsByDedupeKey(anyString())).thenReturn(false);

        service.createMatchCreatedEvent(matchId, recipientId, senderId, OffsetDateTime.now());

        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxRepo).insert(any(), typeCaptor.capture(),
                eq(recipientId), eq(senderId), eq(matchId), isNull(),
                isNull(), isNull(), anyString(), isNull(), anyString(), isNull(), any());

        assertThat(typeCaptor.getValue()).isEqualTo("MATCH_CREATED");
    }

    @Test
    void createLikeReceivedEvent_insertsWithCorrectType() {
        when(outboxRepo.existsByDedupeKey(anyString())).thenReturn(false);

        service.createLikeReceivedEvent(actionId, recipientId, senderId, OffsetDateTime.now());

        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxRepo).insert(any(), typeCaptor.capture(),
                eq(recipientId), eq(senderId), isNull(), isNull(),
                eq(actionId), isNull(), anyString(), isNull(), anyString(), isNull(), any());

        assertThat(typeCaptor.getValue()).isEqualTo("LIKE_RECEIVED");
    }

    @Test
    void createMarketingEvent_insertsWithCollapseKey() {
        when(outboxRepo.existsByDedupeKey(anyString())).thenReturn(false);

        service.createMarketingEvent(campaignId, recipientId, OffsetDateTime.now());

        ArgumentCaptor<String> collapseCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxRepo).insert(any(), eq("MARKETING"),
                eq(recipientId), isNull(), isNull(), isNull(),
                isNull(), eq(campaignId), anyString(), collapseCaptor.capture(),
                anyString(), isNull(), any());

        assertThat(collapseCaptor.getValue()).contains(campaignId.toString());
    }

    @Test
    void createAccountAlertEvent_insertsWithCorrectType() {
        UUID businessEventId = UUID.randomUUID();
        when(outboxRepo.existsByDedupeKey(anyString())).thenReturn(false);

        service.createAccountAlertEvent(recipientId, "VERIFICATION_APPROVED",
                businessEventId, OffsetDateTime.now());

        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxRepo).insert(any(), typeCaptor.capture(),
                eq(recipientId), isNull(), isNull(), isNull(),
                isNull(), isNull(), anyString(), isNull(), anyString(), isNull(), any());

        assertThat(typeCaptor.getValue()).isEqualTo("ACCOUNT_ALERT");
    }
}
