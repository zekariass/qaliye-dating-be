package com.qaliye.backend.chat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaliye.backend.chat.repository.ChatOutboxRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class ChatOutboxService {

    private final ChatOutboxRepository outboxRepo;
    private final ObjectMapper objectMapper;

    public ChatOutboxService(ChatOutboxRepository outboxRepo, ObjectMapper objectMapper) {
        this.outboxRepo = outboxRepo;
        this.objectMapper = objectMapper;
    }

    public void createMessageCreatedEvent(UUID matchId, UUID messageId, long sequenceNumber,
                                          UUID senderUserId, String messageType, String body,
                                          OffsetDateTime occurredAt) {
        String topic = "match:" + matchId + ":events";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message_id", messageId.toString());
        data.put("sequence_number", sequenceNumber);
        data.put("sender_user_id", senderUserId.toString());
        data.put("message_type", messageType);
        data.put("body", body);
        data.put("created_at", occurredAt.toString());

        Map<String, Object> envelope = buildEnvelope(UUID.randomUUID(), "chat.message.created",
                matchId, occurredAt, data);
        outboxRepo.insert(UUID.randomUUID(), "chat.message.created", matchId, null,
                topic, toJson(envelope), occurredAt);
    }

    public void createReceiptUpdatedEvent(UUID matchId, UUID updaterUserId,
                                          long deliveredSequence, long readSequence,
                                          OffsetDateTime occurredAt) {
        String topic = "match:" + matchId + ":events";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", updaterUserId.toString());
        data.put("delivered_sequence", deliveredSequence);
        data.put("read_sequence", readSequence);
        data.put("updated_at", occurredAt.toString());

        Map<String, Object> envelope = buildEnvelope(UUID.randomUUID(), "chat.receipt.updated",
                matchId, occurredAt, data);
        outboxRepo.insert(UUID.randomUUID(), "chat.receipt.updated", matchId, null,
                topic, toJson(envelope), occurredAt);
    }

    public void createMatchEndedEvent(UUID matchId, String endReason, OffsetDateTime occurredAt) {
        String topic = "match:" + matchId + ":events";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("end_reason", endReason);
        data.put("ended_at", occurredAt.toString());

        Map<String, Object> envelope = buildEnvelope(UUID.randomUUID(), "chat.match.ended",
                matchId, occurredAt, data);
        outboxRepo.insert(UUID.randomUUID(), "chat.match.ended", matchId, null,
                topic, toJson(envelope), occurredAt);
    }

    public void createInboxMatchUpdatedEvent(UUID matchId, UUID recipientUserId, OffsetDateTime occurredAt) {
        String topic = "user:" + recipientUserId + ":inbox";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("match_id", matchId.toString());

        Map<String, Object> envelope = buildEnvelope(UUID.randomUUID(), "inbox.match.updated",
                matchId, occurredAt, data);
        outboxRepo.insert(UUID.randomUUID(), "inbox.match.updated", matchId, recipientUserId,
                topic, toJson(envelope), occurredAt);
    }

    public void createInboxMatchRemovedEvent(UUID matchId, UUID recipientUserId, OffsetDateTime occurredAt) {
        String topic = "user:" + recipientUserId + ":inbox";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("match_id", matchId.toString());

        Map<String, Object> envelope = buildEnvelope(UUID.randomUUID(), "inbox.match.removed",
                matchId, occurredAt, data);
        outboxRepo.insert(UUID.randomUUID(), "inbox.match.removed", matchId, recipientUserId,
                topic, toJson(envelope), occurredAt);
    }

    private Map<String, Object> buildEnvelope(UUID eventId, String eventType, UUID matchId,
                                               OffsetDateTime occurredAt, Map<String, Object> data) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("event_id", eventId.toString());
        env.put("event_type", eventType);
        env.put("version", 1);
        env.put("occurred_at", occurredAt.toString());
        env.put("match_id", matchId.toString());
        env.put("data", data);
        return env;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox payload", e);
        }
    }
}
