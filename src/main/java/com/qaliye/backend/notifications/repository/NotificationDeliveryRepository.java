package com.qaliye.backend.notifications.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class NotificationDeliveryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationDeliveryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record DeliveryRow(
            UUID id,
            UUID notificationOutboxEventId,
            UUID notificationDeviceId,
            String status,
            String resolutionCode,
            int attemptCount,
            OffsetDateTime availableAt,
            String providerTicketId,
            OffsetDateTime nextReceiptCheckAt,
            OffsetDateTime receiptDeadlineAt,
            String lastErrorCode,
            String lastError
    ) {}

    private static final String INSERT_SQL = """
            INSERT INTO notification_deliveries
                (id, notification_outbox_event_id, notification_device_id,
                 status, attempt_count, available_at)
            VALUES
                (:id, :outboxEventId, :deviceId, 'PENDING', 0, CURRENT_TIMESTAMP)
            ON CONFLICT (notification_outbox_event_id, notification_device_id) DO NOTHING
            """;

    private static final String CLAIM_PENDING_SQL = """
            UPDATE notification_deliveries
            SET status           = 'PROCESSING',
                locked_at        = NOW(),
                locked_by        = :workerId,
                lease_expires_at = NOW() + (:leaseSeconds || ' seconds')::INTERVAL,
                attempt_count    = attempt_count + 1
            WHERE id IN (
                SELECT id FROM notification_deliveries
                WHERE status = 'PENDING'
                  AND available_at <= NOW()
                ORDER BY available_at, created_at
                LIMIT :batchSize
                FOR UPDATE SKIP LOCKED
            )
            RETURNING id, notification_outbox_event_id, notification_device_id,
                      status, resolution_code, attempt_count, available_at,
                      provider_ticket_id, next_receipt_check_at, receipt_deadline_at,
                      last_error_code, last_error
            """;

    private static final String RECLAIM_EXPIRED_SQL = """
            UPDATE notification_deliveries
            SET status           = 'PROCESSING',
                locked_at        = NOW(),
                locked_by        = :workerId,
                lease_expires_at = NOW() + (:leaseSeconds || ' seconds')::INTERVAL,
                attempt_count    = attempt_count + 1
            WHERE id IN (
                SELECT id FROM notification_deliveries
                WHERE status = 'PROCESSING'
                  AND lease_expires_at < NOW()
                ORDER BY lease_expires_at
                LIMIT :batchSize
                FOR UPDATE SKIP LOCKED
            )
            RETURNING id, notification_outbox_event_id, notification_device_id,
                      status, resolution_code, attempt_count, available_at,
                      provider_ticket_id, next_receipt_check_at, receipt_deadline_at,
                      last_error_code, last_error
            """;

    private static final String CLAIM_FOR_RECEIPT_SQL = """
            UPDATE notification_deliveries
            SET status           = 'PROCESSING',
                locked_at        = NOW(),
                locked_by        = :workerId,
                lease_expires_at = NOW() + (:leaseSeconds || ' seconds')::INTERVAL,
                attempt_count    = attempt_count + 1
            WHERE id IN (
                SELECT id FROM notification_deliveries
                WHERE status = 'SUBMITTED'
                  AND next_receipt_check_at <= NOW()
                  AND provider_ticket_id IS NOT NULL
                ORDER BY next_receipt_check_at
                LIMIT :batchSize
                FOR UPDATE SKIP LOCKED
            )
            RETURNING id, notification_outbox_event_id, notification_device_id,
                      status, resolution_code, attempt_count, available_at,
                      provider_ticket_id, next_receipt_check_at, receipt_deadline_at,
                      last_error_code, last_error
            """;

    private static final String MARK_SUBMITTED_SQL = """
            UPDATE notification_deliveries
            SET status             = 'SUBMITTED',
                provider_ticket_id = :ticketId,
                submitted_at       = NOW(),
                next_receipt_check_at = :nextReceiptCheckAt,
                receipt_deadline_at   = :receiptDeadlineAt,
                locked_at          = NULL,
                locked_by          = NULL,
                lease_expires_at   = NULL
            WHERE id = :id
            """;

    private static final String MARK_SKIPPED_SQL = """
            UPDATE notification_deliveries
            SET status           = 'SKIPPED',
                resolution_code  = :resolutionCode,
                locked_at        = NULL,
                locked_by        = NULL,
                lease_expires_at = NULL
            WHERE id = :id
            """;

    private static final String MARK_CONFIRMED_SQL = """
            UPDATE notification_deliveries
            SET status              = 'CONFIRMED',
                confirmed_at        = NOW(),
                receipt_checked_at  = NOW(),
                locked_at           = NULL,
                locked_by           = NULL,
                lease_expires_at    = NULL
            WHERE id = :id
            """;

    private static final String MARK_UNKNOWN_SQL = """
            UPDATE notification_deliveries
            SET status             = 'UNKNOWN',
                receipt_checked_at = NOW(),
                locked_at          = NULL,
                locked_by          = NULL,
                lease_expires_at   = NULL
            WHERE id = :id
            """;

    private static final String REQUEUE_SQL = """
            UPDATE notification_deliveries
            SET status           = 'PENDING',
                locked_at        = NULL,
                locked_by        = NULL,
                lease_expires_at = NULL,
                available_at     = NOW() + (:delaySeconds || ' seconds')::INTERVAL,
                last_error_code  = :lastErrorCode,
                last_error       = :lastError
            WHERE id = :id
            """;

    private static final String MARK_FAILED_SQL = """
            UPDATE notification_deliveries
            SET status           = 'FAILED',
                last_error_code  = :lastErrorCode,
                last_error       = :lastError,
                locked_at        = NULL,
                locked_by        = NULL,
                lease_expires_at = NULL
            WHERE id = :id
            """;

    private static final String COUNT_BY_OUTBOX_AND_STATUS_SQL = """
            SELECT COUNT(1) FROM notification_deliveries
            WHERE notification_outbox_event_id = :outboxEventId
              AND status IN (:statuses)
            """;

    public void insert(UUID id, UUID outboxEventId, UUID deviceId) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("outboxEventId", outboxEventId)
                .addValue("deviceId", deviceId);
        jdbc.update(INSERT_SQL, params);
    }

    public List<DeliveryRow> claimPending(String workerId, int batchSize, int leaseSeconds) {
        var params = new MapSqlParameterSource()
                .addValue("workerId", workerId)
                .addValue("batchSize", batchSize)
                .addValue("leaseSeconds", leaseSeconds);
        return jdbc.query(CLAIM_PENDING_SQL, params, this::mapRow);
    }

    public List<DeliveryRow> reclaimExpired(String workerId, int batchSize, int leaseSeconds) {
        var params = new MapSqlParameterSource()
                .addValue("workerId", workerId)
                .addValue("batchSize", batchSize)
                .addValue("leaseSeconds", leaseSeconds);
        return jdbc.query(RECLAIM_EXPIRED_SQL, params, this::mapRow);
    }

    public List<DeliveryRow> claimForReceiptCheck(String workerId, int batchSize, int leaseSeconds) {
        var params = new MapSqlParameterSource()
                .addValue("workerId", workerId)
                .addValue("batchSize", batchSize)
                .addValue("leaseSeconds", leaseSeconds);
        return jdbc.query(CLAIM_FOR_RECEIPT_SQL, params, this::mapRow);
    }

    public void markSubmitted(UUID id, String ticketId,
                              OffsetDateTime nextReceiptCheckAt,
                              OffsetDateTime receiptDeadlineAt) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("ticketId", ticketId)
                .addValue("nextReceiptCheckAt", nextReceiptCheckAt)
                .addValue("receiptDeadlineAt", receiptDeadlineAt);
        jdbc.update(MARK_SUBMITTED_SQL, params);
    }

    public void markSkipped(UUID id, String resolutionCode) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("resolutionCode", resolutionCode);
        jdbc.update(MARK_SKIPPED_SQL, params);
    }

    public void markConfirmed(UUID id) {
        jdbc.update(MARK_CONFIRMED_SQL, new MapSqlParameterSource("id", id));
    }

    public void markUnknown(UUID id) {
        jdbc.update(MARK_UNKNOWN_SQL, new MapSqlParameterSource("id", id));
    }

    public void requeue(UUID id, long delaySeconds, String lastErrorCode, String lastError) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("delaySeconds", delaySeconds)
                .addValue("lastErrorCode", lastErrorCode)
                .addValue("lastError", truncate(lastError, 2000));
        jdbc.update(REQUEUE_SQL, params);
    }

    public void markFailed(UUID id, String lastErrorCode, String lastError) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("lastErrorCode", lastErrorCode)
                .addValue("lastError", truncate(lastError, 2000));
        jdbc.update(MARK_FAILED_SQL, params);
    }

    public long countByOutboxEventAndStatuses(UUID outboxEventId, List<String> statuses) {
        var params = new MapSqlParameterSource()
                .addValue("outboxEventId", outboxEventId)
                .addValue("statuses", statuses);
        Long count = jdbc.queryForObject(COUNT_BY_OUTBOX_AND_STATUS_SQL, params, Long.class);
        return count != null ? count : 0L;
    }

    private DeliveryRow mapRow(ResultSet rs, int n) throws SQLException {
        return new DeliveryRow(
                rs.getObject("id", UUID.class),
                rs.getObject("notification_outbox_event_id", UUID.class),
                rs.getObject("notification_device_id", UUID.class),
                rs.getString("status"),
                rs.getString("resolution_code"),
                rs.getInt("attempt_count"),
                rs.getObject("available_at", OffsetDateTime.class),
                rs.getString("provider_ticket_id"),
                rs.getObject("next_receipt_check_at", OffsetDateTime.class),
                rs.getObject("receipt_deadline_at", OffsetDateTime.class),
                rs.getString("last_error_code"),
                rs.getString("last_error")
        );
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
