package com.qaliye.backend.chat.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class ChatOutboxRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ChatOutboxRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record OutboxRow(
            UUID id, String eventType, UUID matchId, UUID recipientUserId,
            String topic, String payloadJson, String status,
            int attemptCount, OffsetDateTime availableAt, OffsetDateTime occurredAt
    ) {}

    private static final String INSERT_SQL = """
            INSERT INTO chat_outbox_events
                (id, event_type, match_id, recipient_user_id, topic, payload,
                 status, attempt_count, available_at, occurred_at)
            VALUES
                (:id, :eventType, :matchId, :recipientUserId, :topic, :payload::jsonb,
                 'PENDING', 0, CURRENT_TIMESTAMP, :occurredAt)
            """;

    private static final String CLAIM_PENDING_SQL = """
            UPDATE chat_outbox_events
            SET status          = 'PROCESSING',
                locked_at       = NOW(),
                locked_by       = :workerId,
                lease_expires_at = NOW() + (:leaseSeconds || ' seconds')::INTERVAL,
                last_attempt_at = NOW(),
                attempt_count   = attempt_count + 1
            WHERE id IN (
                SELECT id FROM chat_outbox_events
                WHERE status = 'PENDING'
                  AND available_at <= NOW()
                ORDER BY available_at, created_at
                LIMIT :batchSize
                FOR UPDATE SKIP LOCKED
            )
            RETURNING id, event_type, match_id, recipient_user_id, topic,
                      payload::text AS payload_json, attempt_count, occurred_at, available_at
            """;

    private static final String RECLAIM_EXPIRED_SQL = """
            UPDATE chat_outbox_events
            SET status          = 'PROCESSING',
                locked_at       = NOW(),
                locked_by       = :workerId,
                lease_expires_at = NOW() + (:leaseSeconds || ' seconds')::INTERVAL,
                last_attempt_at = NOW(),
                attempt_count   = attempt_count + 1
            WHERE id IN (
                SELECT id FROM chat_outbox_events
                WHERE status = 'PROCESSING'
                  AND lease_expires_at < NOW()
                ORDER BY lease_expires_at
                LIMIT :batchSize
                FOR UPDATE SKIP LOCKED
            )
            RETURNING id, event_type, match_id, recipient_user_id, topic,
                      payload::text AS payload_json, attempt_count, occurred_at, available_at
            """;

    private static final String MARK_PUBLISHED_SQL = """
            UPDATE chat_outbox_events
            SET status       = 'PUBLISHED',
                published_at = NOW(),
                locked_at    = NULL,
                locked_by    = NULL,
                lease_expires_at = NULL
            WHERE id = :id
            """;

    private static final String REQUEUE_SQL = """
            UPDATE chat_outbox_events
            SET status          = 'PENDING',
                locked_at       = NULL,
                locked_by       = NULL,
                lease_expires_at = NULL,
                available_at    = NOW() + (:delaySeconds || ' seconds')::INTERVAL,
                last_error      = :lastError
            WHERE id = :id
            """;

    private static final String MARK_FAILED_SQL = """
            UPDATE chat_outbox_events
            SET status     = 'FAILED',
                last_error = :lastError,
                locked_at  = NULL,
                locked_by  = NULL,
                lease_expires_at = NULL
            WHERE id = :id
            """;

    public void insert(UUID id, String eventType, UUID matchId, UUID recipientUserId,
                       String topic, String payloadJson, OffsetDateTime occurredAt) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("eventType", eventType)
                .addValue("matchId", matchId)
                .addValue("recipientUserId", recipientUserId)
                .addValue("topic", topic)
                .addValue("payload", payloadJson)
                .addValue("occurredAt", occurredAt);
        jdbc.update(INSERT_SQL, params);
    }

    public List<OutboxRow> claimPending(String workerId, int batchSize, int leaseSeconds) {
        var params = new MapSqlParameterSource()
                .addValue("workerId", workerId)
                .addValue("batchSize", batchSize)
                .addValue("leaseSeconds", leaseSeconds);
        return jdbc.query(CLAIM_PENDING_SQL, params, this::mapRow);
    }

    public List<OutboxRow> reclaimExpired(String workerId, int batchSize, int leaseSeconds) {
        var params = new MapSqlParameterSource()
                .addValue("workerId", workerId)
                .addValue("batchSize", batchSize)
                .addValue("leaseSeconds", leaseSeconds);
        return jdbc.query(RECLAIM_EXPIRED_SQL, params, this::mapRow);
    }

    public void markPublished(UUID id) {
        jdbc.update(MARK_PUBLISHED_SQL, new MapSqlParameterSource("id", id));
    }

    public void requeue(UUID id, long delaySeconds, String lastError) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("delaySeconds", delaySeconds)
                .addValue("lastError", truncate(lastError, 2000));
        jdbc.update(REQUEUE_SQL, params);
    }

    public void markFailed(UUID id, String lastError) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("lastError", truncate(lastError, 2000));
        jdbc.update(MARK_FAILED_SQL, params);
    }

    private OutboxRow mapRow(ResultSet rs, int n) throws SQLException {
        return new OutboxRow(
                rs.getObject("id", UUID.class),
                rs.getString("event_type"),
                rs.getObject("match_id", UUID.class),
                rs.getObject("recipient_user_id", UUID.class),
                rs.getString("topic"),
                rs.getString("payload_json"),
                null,
                rs.getInt("attempt_count"),
                rs.getObject("available_at", OffsetDateTime.class),
                rs.getObject("occurred_at", OffsetDateTime.class)
        );
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
