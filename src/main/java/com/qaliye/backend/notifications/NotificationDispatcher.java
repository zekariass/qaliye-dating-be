package com.qaliye.backend.notifications;

import com.qaliye.backend.notifications.service.NotificationOutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationOutboxService outboxService;

    public NotificationDispatcher(NotificationOutboxService outboxService) {
        this.outboxService = outboxService;
    }

    public void dispatchMatchNotification(UUID userOneId, UUID userTwoId, UUID matchId) {
        try {
            OffsetDateTime now = OffsetDateTime.now();
            outboxService.createMatchCreatedEvent(matchId, userOneId, userTwoId, now);
            outboxService.createMatchCreatedEvent(matchId, userTwoId, userOneId, now);
        } catch (Exception e) {
            log.error("dispatchMatchNotification failed for match {}: {}", matchId, e.getMessage());
        }
    }

    public void dispatchLikeNotification(UUID actorId, UUID targetId, UUID discoveryActionId) {
        try {
            outboxService.createLikeReceivedEvent(discoveryActionId, targetId, actorId, OffsetDateTime.now());
        } catch (Exception e) {
            log.error("dispatchLikeNotification failed for action {}: {}", discoveryActionId, e.getMessage());
        }
    }

    public void dispatchSuperLikeNotification(UUID actorId, UUID targetId, UUID discoveryActionId) {
        try {
            outboxService.createSuperLikeReceivedEvent(discoveryActionId, targetId, actorId, OffsetDateTime.now());
        } catch (Exception e) {
            log.error("dispatchSuperLikeNotification failed for action {}: {}", discoveryActionId, e.getMessage());
        }
    }

    public void dispatchMessageNotification(UUID recipientId, UUID matchId,
                                            String senderDisplayName) {
        log.debug("dispatchMessageNotification: use MessageCommandService outbox integration instead; "
                + "skipping legacy dispatch for match {}", matchId);
    }

    public void dispatchVerificationApprovedNotification(UUID userId) {
        try {
            outboxService.createAccountAlertEvent(userId, "VERIFICATION_APPROVED",
                    UUID.randomUUID(), OffsetDateTime.now());
        } catch (Exception e) {
            log.error("dispatchVerificationApprovedNotification failed for user {}: {}",
                    userId, e.getMessage());
        }
    }

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
