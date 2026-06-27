package com.qaliye.backend.notifications.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaliye.backend.notifications.repository.NotificationOutboxRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class NotificationOutboxService {

    private static final int CHAT_MESSAGE_TTL_MINUTES = 15;

    private final NotificationOutboxRepository outboxRepo;
    private final ObjectMapper objectMapper;

    public NotificationOutboxService(NotificationOutboxRepository outboxRepo,
                                     ObjectMapper objectMapper) {
        this.outboxRepo = outboxRepo;
        this.objectMapper = objectMapper;
    }

    public void createChatMessageEvent(UUID messageId, UUID matchId,
                                       UUID senderUserId, UUID recipientUserId,
                                       OffsetDateTime occurredAt) {
        String dedupeKey = "chat-message:" + messageId + ":" + recipientUserId;
        String collapseKey = "chat:" + matchId;

        if (outboxRepo.existsByDedupeKey(dedupeKey)) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("notification_type", "CHAT_MESSAGE");
        payload.put("notification_id", UUID.randomUUID().toString());
        payload.put("match_id", matchId.toString());
        payload.put("message_id", messageId.toString());

        OffsetDateTime expiresAt = occurredAt.plusMinutes(CHAT_MESSAGE_TTL_MINUTES);

        outboxRepo.insert(
                UUID.randomUUID(), "CHAT_MESSAGE",
                recipientUserId, senderUserId,
                matchId, messageId,
                null, null,
                dedupeKey, collapseKey,
                toJson(payload), expiresAt, occurredAt
        );
    }

    public void createMatchCreatedEvent(UUID matchId, UUID recipientUserId,
                                        UUID actorUserId, OffsetDateTime occurredAt) {
        String dedupeKey = "match-created:" + matchId + ":" + recipientUserId;

        if (outboxRepo.existsByDedupeKey(dedupeKey)) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("notification_type", "MATCH_CREATED");
        payload.put("notification_id", UUID.randomUUID().toString());
        payload.put("match_id", matchId.toString());

        outboxRepo.insert(
                UUID.randomUUID(), "MATCH_CREATED",
                recipientUserId, actorUserId,
                matchId, null,
                null, null,
                dedupeKey, null,
                toJson(payload), null, occurredAt
        );
    }

    public void createLikeReceivedEvent(UUID discoveryActionId, UUID recipientUserId,
                                        UUID actorUserId, OffsetDateTime occurredAt) {
        String dedupeKey = "like:" + discoveryActionId + ":" + recipientUserId;

        if (outboxRepo.existsByDedupeKey(dedupeKey)) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("notification_type", "LIKE_RECEIVED");
        payload.put("notification_id", UUID.randomUUID().toString());
        payload.put("discovery_action_id", discoveryActionId.toString());

        outboxRepo.insert(
                UUID.randomUUID(), "LIKE_RECEIVED",
                recipientUserId, actorUserId,
                null, null,
                discoveryActionId, null,
                dedupeKey, null,
                toJson(payload), null, occurredAt
        );
    }

    public void createAccountAlertEvent(UUID recipientUserId, String alertCode,
                                        UUID businessEventId, OffsetDateTime occurredAt) {
        String dedupeKey = "account-alert:" + alertCode + ":" + recipientUserId
                + ":" + businessEventId;

        if (outboxRepo.existsByDedupeKey(dedupeKey)) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("notification_type", "ACCOUNT_ALERT");
        payload.put("notification_id", UUID.randomUUID().toString());
        payload.put("alert_code", alertCode);

        outboxRepo.insert(
                UUID.randomUUID(), "ACCOUNT_ALERT",
                recipientUserId, null,
                null, null,
                null, null,
                dedupeKey, null,
                toJson(payload), null, occurredAt
        );
    }

    public void createMarketingEvent(UUID campaignId, UUID recipientUserId,
                                     OffsetDateTime occurredAt) {
        String dedupeKey = "marketing:" + campaignId + ":" + recipientUserId;
        String collapseKey = "marketing:" + campaignId;

        if (outboxRepo.existsByDedupeKey(dedupeKey)) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("notification_type", "MARKETING");
        payload.put("notification_id", UUID.randomUUID().toString());
        payload.put("campaign_id", campaignId.toString());

        outboxRepo.insert(
                UUID.randomUUID(), "MARKETING",
                recipientUserId, null,
                null, null,
                null, campaignId,
                dedupeKey, collapseKey,
                toJson(payload), null, occurredAt
        );
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize notification payload", e);
        }
    }
}
