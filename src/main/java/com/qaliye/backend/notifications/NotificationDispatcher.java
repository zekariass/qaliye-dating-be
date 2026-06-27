package com.qaliye.backend.notifications;

import com.qaliye.backend.notifications.service.NotificationOutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationOutboxService outboxService;

    public NotificationDispatcher(NotificationOutboxService outboxService) {
        this.outboxService = outboxService;
    }

    @Async
    @Transactional
    public void dispatchMatchNotification(UUID userOneId, UUID userTwoId, UUID matchId) {
        try {
            OffsetDateTime now = OffsetDateTime.now();
            outboxService.createMatchCreatedEvent(matchId, userOneId, userTwoId, now);
            outboxService.createMatchCreatedEvent(matchId, userTwoId, userOneId, now);
        } catch (Exception e) {
            log.error("dispatchMatchNotification failed for match {}: {}", matchId, e.getMessage());
        }
    }

    @Async
    @Transactional
    public void dispatchMessageNotification(UUID recipientId, UUID matchId,
                                            String senderDisplayName) {
        log.debug("dispatchMessageNotification: use MessageCommandService outbox integration instead; "
                + "skipping legacy dispatch for match {}", matchId);
    }

    @Async
    @Transactional
    public void dispatchVerificationApprovedNotification(UUID userId) {
        try {
            outboxService.createAccountAlertEvent(userId, "VERIFICATION_APPROVED",
                    UUID.randomUUID(), OffsetDateTime.now());
        } catch (Exception e) {
            log.error("dispatchVerificationApprovedNotification failed for user {}: {}",
                    userId, e.getMessage());
        }
    }

    @Async
    @Transactional
    public void dispatchVerificationRejectedNotification(UUID userId, String rejectionReason) {
        try {
            outboxService.createAccountAlertEvent(userId, "VERIFICATION_REJECTED",
                    UUID.randomUUID(), OffsetDateTime.now());
        } catch (Exception e) {
            log.error("dispatchVerificationRejectedNotification failed for user {}: {}",
                    userId, e.getMessage());
        }
    }
}
