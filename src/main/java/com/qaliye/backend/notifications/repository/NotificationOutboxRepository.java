package com.qaliye.backend.notifications.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class NotificationOutboxRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationOutboxRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record OutboxRow(
            UUID id,
            String notificationType,
            UUID recipientUserId,
            UUID actorUserId,
            UUID matchId,
            UUID messageId,
            UUID discoveryActionId,
            UUID campaignId,
            String dedupeKey,
            String collapseKey,
            String payloadJson,
            String status,
            int attemptCount,
            OffsetDateTime availableAt,
            OffsetDateTime expiresAt,
            OffsetDateTime occurredAt
    ) {}

    private static final String INSERT_SQL = """
            INSERT INTO notification_outbox_events
                (id, notification_type, recipient_user_id, actor_user_id, match_id,
                 message_id, discovery_action_id, campaign_id, dedupe_key, collapse_key,
                 payload, status, attempt_count, available_at, expires_at, occurred_at)
            VALUES
                (:id, :notificationType, :recipientUserId, :actorUserId, :matchId,
                 :messageId, :discoveryActionId, :campaignId, :dedupeKey, :collapseKey,
                 :payload::jsonb, 'PENDING', 0, CURRENT_TIMESTAMP, :expiresAt, :occurredAt)
            ON CONFLICT (dedupe_key) DO NOTHING
            """;

    private static final String CLAIM_PENDING_SQL = """
            UPDATE notification_outbox_events
            SET status          = 'PROCESSING',
                locked_at       = NOW(),
                locked_by       = :workerId,
                lease_expires_at = NOW() + (:leaseSeconds || ' seconds')::INTERVAL,
                attempt_count   = attempt_count + 1
            WHERE id IN (
                SELECT id FROM notification_outbox_events
                WHERE status = 'PENDING'
                  AND available_at <= NOW()
                ORDER BY available_at, created_at
                LIMIT :batchSize
                FOR UPDATE SKIP LOCKED
            )
            RETURNING id, notification_type, recipient_user_id, actor_user_id, match_id,
                      message_id, discovery_action_id, campaign_id, dedupe_key, collapse_key,
                      payload::text AS payload_json, status, attempt_count,
                      available_at, expires_at, occurred_at
            """;

    private static final String RECLAIM_EXPIRED_SQL = """
            UPDATE notification_outbox_events
            SET status          = 'PROCESSING',
                locked_at       = NOW(),
                locked_by       = :workerId,
                lease_expires_at = NOW() + (:leaseSeconds || ' seconds')::INTERVAL,
                attempt_count   = attempt_count + 1
            WHERE id IN (
                SELECT id FROM notification_outbox_events
                WHERE status = 'PROCESSING'
                  AND lease_expires_at < NOW()
                ORDER BY lease_expires_at
                LIMIT :batchSize
                FOR UPDATE SKIP LOCKED
            )
            RETURNING id, notification_type, recipient_user_id, actor_user_id, match_id,
                      message_id, discovery_action_id, campaign_id, dedupe_key, collapse_key,
                      payload::text AS payload_json, status, attempt_count,
                      available_at, expires_at, occurred_at
            """;

    private static final String MARK_FANOUT_COMPLETE_SQL = """
            UPDATE notification_outbox_events
            SET status              = 'FANOUT_COMPLETE',
                fanout_completed_at = NOW(),
                locked_at           = NULL,
                locked_by           = NULL,
                lease_expires_at    = NULL
            WHERE id = :id
            """;

    private static final String MARK_SKIPPED_SQL = """
            UPDATE notification_outbox_events
            SET status           = 'SKIPPED',
                last_error       = :reason,
                locked_at        = NULL,
                locked_by        = NULL,
                lease_expires_at = NULL
            WHERE id = :id
            """;

    private static final String REQUEUE_SQL = """
            UPDATE notification_outbox_events
            SET status           = 'PENDING',
                locked_at        = NULL,
                locked_by        = NULL,
                lease_expires_at = NULL,
                available_at     = NOW() + (:delaySeconds || ' seconds')::INTERVAL,
                last_error       = :lastError
            WHERE id = :id
            """;

    private static final String MARK_FAILED_SQL = """
            UPDATE notification_outbox_events
            SET status           = 'FAILED',
                last_error       = :lastError,
                locked_at        = NULL,
                locked_by        = NULL,
                lease_expires_at = NULL
            WHERE id = :id
            """;

    private static final String FIND_BY_ID_SQL = """
            SELECT id, notification_type, recipient_user_id, actor_user_id, match_id,
                   message_id, discovery_action_id, campaign_id, dedupe_key, collapse_key,
                   payload::text AS payload_json, status, attempt_count,
                   available_at, expires_at, occurred_at
            FROM notification_outbox_events
            WHERE id = :id
            """;

    private static final String EXISTS_BY_DEDUPE_SQL = """
            SELECT COUNT(1) FROM notification_outbox_events WHERE dedupe_key = :dedupeKey
            """;

    public void insert(UUID id, String notificationType, UUID recipientUserId,
                       UUID actorUserId, UUID matchId, UUID messageId,
                       UUID discoveryActionId, UUID campaignId,
                       String dedupeKey, String collapseKey,
                       String payloadJson, OffsetDateTime expiresAt,
                       OffsetDateTime occurredAt) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("notificationType", notificationType)
                .addValue("recipientUserId", recipientUserId)
                .addValue("actorUserId", actorUserId)
                .addValue("matchId", matchId)
                .addValue("messageId", messageId)
                .addValue("discoveryActionId", discoveryActionId)
                .addValue("campaignId", campaignId)
                .addValue("dedupeKey", dedupeKey)
                .addValue("collapseKey", collapseKey)
                .addValue("payload", payloadJson)
                .addValue("expiresAt", expiresAt)
                .addValue("occurredAt", occurredAt);
        jdbc.update(INSERT_SQL, params);
    }

    public Optional<OutboxRow> findById(UUID id) {
        List<OutboxRow> rows = jdbc.query(FIND_BY_ID_SQL,
                new MapSqlParameterSource("id", id), this::mapRow);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public boolean existsByDedupeKey(String dedupeKey) {
        Integer count = jdbc.queryForObject(EXISTS_BY_DEDUPE_SQL,
                Map.of("dedupeKey", dedupeKey), Integer.class);
        return count != null && count > 0;
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

    public void markFanoutComplete(UUID id) {
        jdbc.update(MARK_FANOUT_COMPLETE_SQL, new MapSqlParameterSource("id", id));
    }

    public void markSkipped(UUID id, String reason) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("reason", truncate(reason, 2000));
        jdbc.update(MARK_SKIPPED_SQL, params);
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
                rs.getString("notification_type"),
                rs.getObject("recipient_user_id", UUID.class),
                rs.getObject("actor_user_id", UUID.class),
                rs.getObject("match_id", UUID.class),
                rs.getObject("message_id", UUID.class),
                rs.getObject("discovery_action_id", UUID.class),
                rs.getObject("campaign_id", UUID.class),
                rs.getString("dedupe_key"),
                rs.getString("collapse_key"),
                rs.getString("payload_json"),
                rs.getString("status"),
                rs.getInt("attempt_count"),
                rs.getObject("available_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("occurred_at", OffsetDateTime.class)
        );
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
