package com.qaliye.backend.notifications.worker;

import com.qaliye.backend.notifications.config.PushProperties;
import com.qaliye.backend.notifications.repository.NotificationDeliveryRepository;
import com.qaliye.backend.notifications.repository.NotificationOutboxRepository;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class NotificationRecoveryWorker implements Job {

    private static final Logger log = LoggerFactory.getLogger(NotificationRecoveryWorker.class);
    private static final String WORKER_ID_PREFIX = "recovery-";

    private static final String CLEAR_EXPIRED_MARKETING_RESERVATIONS_SQL = """
            UPDATE user_notification_preferences
            SET marketing_reservation_event_id   = NULL,
                marketing_reservation_expires_at = NULL,
                updated_at                       = NOW()
            WHERE marketing_reservation_expires_at IS NOT NULL
              AND marketing_reservation_expires_at < NOW()
            """;

    private static final String EXPIRE_OVERDUE_OUTBOX_SQL = """
            UPDATE notification_outbox_events
            SET status     = 'SKIPPED',
                last_error = 'Expired: expires_at passed while still PENDING'
            WHERE status    = 'PENDING'
              AND expires_at IS NOT NULL
              AND expires_at < NOW()
            """;

    private static final String EXPIRE_OVERDUE_DELIVERIES_SQL = """
            UPDATE notification_deliveries
            SET status          = 'UNKNOWN',
                receipt_checked_at = NOW(),
                locked_at        = NULL,
                locked_by        = NULL,
                lease_expires_at = NULL
            WHERE status = 'SUBMITTED'
              AND receipt_deadline_at IS NOT NULL
              AND receipt_deadline_at < NOW()
            """;

    @Autowired private NotificationOutboxRepository outboxRepo;
    @Autowired private NotificationDeliveryRepository deliveryRepo;
    @Autowired private PushProperties pushProperties;
    @Autowired private NamedParameterJdbcTemplate jdbc;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        String workerId = WORKER_ID_PREFIX + context.getFireInstanceId();

        try {
            reclaimExpiredOutboxLeases(workerId);
            reclaimExpiredDeliveryLeases(workerId);
            clearExpiredMarketingReservations();
            expireOverdueOutboxEvents();
            expireOverdueDeliveries();
        } catch (Exception e) {
            log.error("NotificationRecoveryWorker failed: {}", e.getMessage());
            throw new JobExecutionException(e, false);
        }
    }

    private void reclaimExpiredOutboxLeases(String workerId) {
        PushProperties.Outbox cfg = pushProperties.getOutbox();
        try {
            var rows = outboxRepo.reclaimExpired(workerId, cfg.getBatchSize(), cfg.getLeaseSeconds());
            if (!rows.isEmpty()) {
                log.info("Recovery: reclaimed {} expired outbox leases", rows.size());
            }
        } catch (Exception e) {
            log.error("Recovery: reclaimExpiredOutboxLeases failed: {}", e.getMessage());
        }
    }

    private void reclaimExpiredDeliveryLeases(String workerId) {
        PushProperties.Delivery cfg = pushProperties.getDelivery();
        try {
            var rows = deliveryRepo.reclaimExpired(
                    workerId, pushProperties.getExpo().getSendBatchSize(), cfg.getLeaseSeconds());
            if (!rows.isEmpty()) {
                log.info("Recovery: reclaimed {} expired delivery leases", rows.size());
            }
        } catch (Exception e) {
            log.error("Recovery: reclaimExpiredDeliveryLeases failed: {}", e.getMessage());
        }
    }

    private void clearExpiredMarketingReservations() {
        try {
            int count = jdbc.update(CLEAR_EXPIRED_MARKETING_RESERVATIONS_SQL, new MapSqlParameterSource());
            if (count > 0) {
                log.info("Recovery: cleared {} expired marketing reservations", count);
            }
        } catch (Exception e) {
            log.error("Recovery: clearExpiredMarketingReservations failed: {}", e.getMessage());
        }
    }

    private void expireOverdueOutboxEvents() {
        try {
            int count = jdbc.update(EXPIRE_OVERDUE_OUTBOX_SQL, new MapSqlParameterSource());
            if (count > 0) {
                log.info("Recovery: expired {} overdue PENDING outbox events", count);
            }
        } catch (Exception e) {
            log.error("Recovery: expireOverdueOutboxEvents failed: {}", e.getMessage());
        }
    }

    private void expireOverdueDeliveries() {
        try {
            int count = jdbc.update(EXPIRE_OVERDUE_DELIVERIES_SQL, new MapSqlParameterSource());
            if (count > 0) {
                log.info("Recovery: marked {} overdue SUBMITTED deliveries as UNKNOWN", count);
            }
        } catch (Exception e) {
            log.error("Recovery: expireOverdueDeliveries failed: {}", e.getMessage());
        }
    }
}
