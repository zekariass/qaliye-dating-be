package com.qaliye.backend.notifications;

import com.qaliye.backend.matchmaking.MatchmakingConstants;
import com.qaliye.backend.notifications.service.NotificationOutboxService;
import com.qaliye.backend.videocall.VideoCallConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Map;
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

    public void dispatchSuperMessageNotification(UUID actorId, UUID targetId, UUID superMessageId) {
        try {
            outboxService.createSuperMessageReceivedEvent(superMessageId, targetId, actorId, OffsetDateTime.now());
        } catch (Exception e) {
            log.error("dispatchSuperMessageNotification failed for message {}: {}", superMessageId, e.getMessage());
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

    public void dispatchPhotoApprovedNotification(UUID userId) {
        try {
            outboxService.createAccountAlertEvent(userId, "PHOTO_APPROVED",
                    UUID.randomUUID(), OffsetDateTime.now());
        } catch (Exception e) {
            log.error("dispatchPhotoApprovedNotification failed for user {}: {}", userId, e.getMessage());
        }
    }

    public void dispatchPhotoRejectedNotification(UUID userId) {
        try {
            outboxService.createAccountAlertEvent(userId, "PHOTO_REJECTED",
                    UUID.randomUUID(), OffsetDateTime.now());
        } catch (Exception e) {
            log.error("dispatchPhotoRejectedNotification failed for user {}: {}", userId, e.getMessage());
        }
    }

    public void dispatchPaymentApprovedNotification(UUID userId) {
        try {
            outboxService.createAccountAlertEvent(userId, "PAYMENT_APPROVED",
                    UUID.randomUUID(), OffsetDateTime.now());
        } catch (Exception e) {
            log.error("dispatchPaymentApprovedNotification failed for user {}: {}", userId, e.getMessage());
        }
    }

    public void dispatchPaymentRejectedNotification(UUID userId) {
        try {
            outboxService.createAccountAlertEvent(userId, "PAYMENT_REJECTED",
                    UUID.randomUUID(), OffsetDateTime.now());
        } catch (Exception e) {
            log.error("dispatchPaymentRejectedNotification failed for user {}: {}", userId, e.getMessage());
        }
    }

    public void dispatchBlindDateRevealNotification(UUID creatorUserId, UUID finalistUserId, UUID sessionId) {
        try {
            OffsetDateTime now = OffsetDateTime.now();
            outboxService.createAccountAlertEvent(creatorUserId, "BLIND_DATE_REVEAL", sessionId, now);
            outboxService.createAccountAlertEvent(finalistUserId, "BLIND_DATE_REVEAL", sessionId, now);
        } catch (Exception e) {
            log.error("dispatchBlindDateRevealNotification failed for session {}: {}", sessionId, e.getMessage());
        }
    }

    public void dispatchBlindDateEliminatedNotification(UUID participantUserId, UUID sessionId) {
        try {
            outboxService.createAccountAlertEvent(participantUserId, "BLIND_DATE_ELIMINATED", sessionId, OffsetDateTime.now());
        } catch (Exception e) {
            log.error("dispatchBlindDateEliminatedNotification failed for session {}: {}", sessionId, e.getMessage());
        }
    }

    public void dispatchBlindDateOutcomeNotification(UUID creatorUserId, UUID finalistUserId,
                                                     UUID sessionId, boolean matched) {
        try {
            String alertCode = matched ? "BLIND_DATE_MATCHED" : "BLIND_DATE_NO_MATCH";
            OffsetDateTime now = OffsetDateTime.now();
            outboxService.createAccountAlertEvent(creatorUserId, alertCode, sessionId, now);
            outboxService.createAccountAlertEvent(finalistUserId, alertCode, sessionId, now);
        } catch (Exception e) {
            log.error("dispatchBlindDateOutcomeNotification failed for session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Sends the same introduction alert to both participants; each
     * recipient's payload carries their own request_id.
     */
    private void dispatchToBothParticipants(String alertCode, UUID introductionId,
                                            UUID requesterUserId, UUID candidateUserId,
                                            UUID requesterRequestId, UUID candidateRequestId) {
        OffsetDateTime now = OffsetDateTime.now();
        outboxService.createAccountAlertEvent(requesterUserId, alertCode, introductionId, now,
                Map.of("introduction_id", introductionId.toString(),
                        "request_id", requesterRequestId.toString()));
        outboxService.createAccountAlertEvent(candidateUserId, alertCode, introductionId, now,
                Map.of("introduction_id", introductionId.toString(),
                        "request_id", candidateRequestId.toString()));
    }

    public void dispatchMatchmakingIntroductionProposedNotification(UUID requesterUserId,
                                                                    UUID candidateUserId,
                                                                    UUID introductionId,
                                                                    UUID requesterRequestId,
                                                                    UUID candidateRequestId) {
        try {
            dispatchToBothParticipants(MatchmakingConstants.ALERT_INTRODUCTION_PROPOSED,
                    introductionId, requesterUserId, candidateUserId,
                    requesterRequestId, candidateRequestId);
        } catch (Exception e) {
            log.error("dispatchMatchmakingIntroductionProposedNotification failed for {}: {}",
                    introductionId, e.getMessage());
        }
    }

    public void dispatchMatchmakingIntroductionDeclinedNotification(UUID requesterUserId,
                                                                    UUID candidateUserId,
                                                                    UUID introductionId,
                                                                    UUID requesterRequestId,
                                                                    UUID candidateRequestId) {
        try {
            dispatchToBothParticipants(MatchmakingConstants.ALERT_INTRODUCTION_DECLINED,
                    introductionId, requesterUserId, candidateUserId,
                    requesterRequestId, candidateRequestId);
        } catch (Exception e) {
            log.error("dispatchMatchmakingIntroductionDeclinedNotification failed for {}: {}",
                    introductionId, e.getMessage());
        }
    }

    public void dispatchMatchmakingIntroductionCancelledNotification(UUID requesterUserId,
                                                                     UUID candidateUserId,
                                                                     UUID introductionId,
                                                                     UUID requesterRequestId,
                                                                     UUID candidateRequestId) {
        try {
            dispatchToBothParticipants(MatchmakingConstants.ALERT_INTRODUCTION_CANCELLED,
                    introductionId, requesterUserId, candidateUserId,
                    requesterRequestId, candidateRequestId);
        } catch (Exception e) {
            log.error("dispatchMatchmakingIntroductionCancelledNotification failed for {}: {}",
                    introductionId, e.getMessage());
        }
    }

    public void dispatchMatchmakingIntroductionExpiredNotification(UUID requesterUserId,
                                                                   UUID candidateUserId,
                                                                   UUID introductionId,
                                                                   UUID requesterRequestId,
                                                                   UUID candidateRequestId) {
        try {
            dispatchToBothParticipants(MatchmakingConstants.ALERT_INTRODUCTION_EXPIRED,
                    introductionId, requesterUserId, candidateUserId,
                    requesterRequestId, candidateRequestId);
        } catch (Exception e) {
            log.error("dispatchMatchmakingIntroductionExpiredNotification failed for {}: {}",
                    introductionId, e.getMessage());
        }
    }

    public void dispatchMatchmakingMatchedNotification(UUID userA, UUID userB,
                                                       UUID introductionId, UUID matchId) {
        try {
            OffsetDateTime now = OffsetDateTime.now();
            Map<String, Object> data = Map.of(
                    "introduction_id", introductionId.toString(),
                    "match_id", matchId.toString());
            outboxService.createAccountAlertEvent(userA,
                    MatchmakingConstants.ALERT_MATCHED, introductionId, now, data);
            outboxService.createAccountAlertEvent(userB,
                    MatchmakingConstants.ALERT_MATCHED, introductionId, now, data);
        } catch (Exception e) {
            log.error("dispatchMatchmakingMatchedNotification failed for introduction {}: {}",
                    introductionId, e.getMessage());
        }
    }

    public void dispatchMatchmakingRequestCreated(UUID userId, UUID requestId) {
        try {
            outboxService.createAccountAlertEvent(userId,
                    MatchmakingConstants.ALERT_REQUEST_CREATED,
                    requestId, OffsetDateTime.now(),
                    Map.of("request_id", requestId.toString()));
        } catch (Exception e) {
            log.error("dispatchMatchmakingRequestCreated failed for request {}: {}",
                    requestId, e.getMessage());
        }
    }

    public void dispatchMatchmakingRequestCancelledNotification(UUID userId, UUID requestId) {
        try {
            outboxService.createAccountAlertEvent(userId,
                    MatchmakingConstants.ALERT_REQUEST_CANCELLED,
                    requestId, OffsetDateTime.now(),
                    Map.of("request_id", requestId.toString()));
        } catch (Exception e) {
            log.error("dispatchMatchmakingRequestCancelledNotification failed for request {}: {}",
                    requestId, e.getMessage());
        }
    }

    public void dispatchMatchmakingRequestExpiredNotification(UUID userId, UUID requestId) {
        try {
            outboxService.createAccountAlertEvent(userId,
                    MatchmakingConstants.ALERT_REQUEST_EXPIRED,
                    requestId, OffsetDateTime.now(),
                    Map.of("request_id", requestId.toString()));
        } catch (Exception e) {
            log.error("dispatchMatchmakingRequestExpiredNotification failed for request {}: {}",
                    requestId, e.getMessage());
        }
    }

    // ── Video-call-request dispatches (videocall package) ──────────────────

    /** Responder gets a new incoming call request. */
    public void dispatchVideoCallRequested(UUID responderUserId, UUID requestId, String callType) {
        dispatchVideoCallRequested(responderUserId, requestId, callType, null);
    }

    /**
     * Responder gets a (re-)ring for an incoming call request.
     * {@code dedupeQualifier} distinguishes re-fires (e.g. {@code "reminder:2"})
     * from the original alert in the outbox dedupe key.
     */
    public void dispatchVideoCallRequested(UUID responderUserId, UUID requestId,
                                           String callType, String dedupeQualifier) {
        try {
            outboxService.createAccountAlertEvent(responderUserId,
                    VideoCallConstants.alertCode(VideoCallConstants.ALERT_REQUESTED,
                            VideoCallConstants.ALERT_AUDIO_REQUESTED, callType),
                    requestId, OffsetDateTime.now(),
                    Map.of("video_call_request_id", requestId.toString()),
                    dedupeQualifier);
        } catch (Exception e) {
            log.error("dispatchVideoCallRequested failed for request {}: {}", requestId, e.getMessage());
        }
    }

    /** Requester learns their request was accepted. */
    public void dispatchVideoCallAccepted(UUID requesterUserId, UUID requestId, String callType) {
        try {
            outboxService.createAccountAlertEvent(requesterUserId,
                    VideoCallConstants.alertCode(VideoCallConstants.ALERT_ACCEPTED,
                            VideoCallConstants.ALERT_AUDIO_ACCEPTED, callType),
                    requestId, OffsetDateTime.now(),
                    Map.of("video_call_request_id", requestId.toString()));
        } catch (Exception e) {
            log.error("dispatchVideoCallAccepted failed for request {}: {}", requestId, e.getMessage());
        }
    }

    /** Requester learns their request was declined. */
    public void dispatchVideoCallDeclined(UUID requesterUserId, UUID requestId, String callType) {
        try {
            outboxService.createAccountAlertEvent(requesterUserId,
                    VideoCallConstants.alertCode(VideoCallConstants.ALERT_DECLINED,
                            VideoCallConstants.ALERT_AUDIO_DECLINED, callType),
                    requestId, OffsetDateTime.now(),
                    Map.of("video_call_request_id", requestId.toString()));
        } catch (Exception e) {
            log.error("dispatchVideoCallDeclined failed for request {}: {}", requestId, e.getMessage());
        }
    }

    /** Responder learns the requester withdrew the pending request. */
    public void dispatchVideoCallCancelled(UUID responderUserId, UUID requestId, String callType) {
        try {
            outboxService.createAccountAlertEvent(responderUserId,
                    VideoCallConstants.alertCode(VideoCallConstants.ALERT_CANCELLED,
                            VideoCallConstants.ALERT_AUDIO_CANCELLED, callType),
                    requestId, OffsetDateTime.now(),
                    Map.of("video_call_request_id", requestId.toString()));
        } catch (Exception e) {
            log.error("dispatchVideoCallCancelled failed for request {}: {}", requestId, e.getMessage());
        }
    }

    /** Requester learns their pending request expired. */
    public void dispatchVideoCallExpired(UUID requesterUserId, UUID requestId, String callType) {
        try {
            outboxService.createAccountAlertEvent(requesterUserId,
                    VideoCallConstants.alertCode(VideoCallConstants.ALERT_EXPIRED,
                            VideoCallConstants.ALERT_AUDIO_EXPIRED, callType),
                    requestId, OffsetDateTime.now(),
                    Map.of("video_call_request_id", requestId.toString()));
        } catch (Exception e) {
            log.error("dispatchVideoCallExpired failed for request {}: {}", requestId, e.getMessage());
        }
    }
}
