package com.qaliye.backend.videocall.service;

import com.qaliye.backend.billing.repository.ActionLimitRepository;
import com.qaliye.backend.billing.service.ActionCostService;
import com.qaliye.backend.billing.service.CreditService;
import com.qaliye.backend.discovery.exception.ActionLimitExceededException;
import com.qaliye.backend.matchmaking.config.MatchmakingProperties;
import com.qaliye.backend.notifications.NotificationDispatcher;
import com.qaliye.backend.videocall.VideoCallConstants;
import com.qaliye.backend.videocall.dto.VideoCallRequestView;
import com.qaliye.backend.videocall.provider.VideoCallProvider;
import com.qaliye.backend.videocall.provider.VideoProperties;
import com.qaliye.backend.videocall.repository.VideoCallRequestRepository;
import com.qaliye.backend.videocall.repository.VideoCallRequestRepository.MatchPair;
import com.qaliye.backend.videocall.repository.VideoCallRequestRepository.VideoCallRequestRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lifecycle for {@code video_call_requests} — a post-match, user-initiated
 * video call available to ANY match source.
 *
 * <p>State machine: PENDING → ACCEPTED → COMPLETED, with PENDING →
 * DECLINED / CANCELLED / EXPIRED. Only the requester pays — a single
 * {@code requester_charge_key} is stamped before the first requester join;
 * the UNIQUE constraint + guarded stamp make double-charge impossible.
 *
 * <p>A block between the pair or a match leaving ACTIVE kills any live
 * request — checked lazily on every load (consistent with the codebase).
 */
@Service
public class VideoCallRequestService {

    private static final Logger log = LoggerFactory.getLogger(VideoCallRequestService.class);

    private final VideoCallRequestRepository requestRepo;
    private final VideoCallProvider videoProvider;
    private final MatchmakingProperties props;
    private final VideoProperties videoProps;
    private final NotificationDispatcher notificationDispatcher;
    private final ActionCostService actionCostService;
    private final ActionLimitRepository actionLimitRepo;
    private final CreditService creditService;

    public VideoCallRequestService(VideoCallRequestRepository requestRepo,
                                   VideoCallProvider videoProvider,
                                   MatchmakingProperties props,
                                   VideoProperties videoProps,
                                   NotificationDispatcher notificationDispatcher,
                                   ActionCostService actionCostService,
                                   ActionLimitRepository actionLimitRepo,
                                   CreditService creditService) {
        this.requestRepo = requestRepo;
        this.videoProvider = videoProvider;
        this.props = props;
        this.videoProps = videoProps;
        this.notificationDispatcher = notificationDispatcher;
        this.actionCostService = actionCostService;
        this.actionLimitRepo = actionLimitRepo;
        this.creditService = creditService;
    }

    /**
     * Creates a PENDING request for the match (idempotent — returns the live
     * one when it already exists). Caller must be a match participant.
     */
    @Transactional
    public VideoCallRequestView createRequest(UUID userId, UUID matchId, String callType) {
        String resolvedType = resolveCallType(callType);
        MatchPair match = loadActiveParticipantMatch(matchId, userId);
        UUID responder = match.userOneId().equals(userId)
                ? match.userTwoId() : match.userOneId();

        if (requestRepo.blockExists(userId, responder)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot request a call with this match.");
        }

        // Idempotent: one live request per match (partial unique index).
        // A live request of the other type is returned as-is — the type is
        // fixed at creation.
        VideoCallRequestRow row = requestRepo.findActiveByMatchId(matchId)
                .orElseGet(() -> {
                    OffsetDateTime expiresAt = OffsetDateTime.now()
                            .plusHours(props.getVideoCallRequestExpiryHours());
                    UUID id = requestRepo.insert(matchId, userId, responder,
                            resolvedType, expiresAt);
                    return requestRepo.findById(id).orElseThrow();
                });

        if (VideoCallConstants.STATUS_PENDING.equals(row.status())) {
            notificationDispatcher.dispatchVideoCallRequested(responder, row.id(), row.callType());
        }

        return toView(row, userId);
    }

    /** Responder accepts → ACCEPTED + channel allocated by the provider. */
    @Transactional
    public VideoCallRequestView accept(UUID requestId, UUID userId) {
        VideoCallRequestRow row = lockAndGuard(requestId, userId);
        requirePending(row);
        requireRole(row, userId, row.responderUserId(), "Only the responder can accept.");

        String channelName = videoProvider.channelFor(requestId);
        if (!requestRepo.accept(requestId, channelName)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Request is no longer pending.");
        }

        VideoCallRequestRow updated = requestRepo.findById(requestId).orElseThrow();
        notificationDispatcher.dispatchVideoCallAccepted(row.requesterUserId(), requestId,
                row.callType());
        return toView(updated, userId);
    }

    /** Responder declines → DECLINED. */
    @Transactional
    public VideoCallRequestView decline(UUID requestId, UUID userId) {
        VideoCallRequestRow row = lockAndGuard(requestId, userId);
        requirePending(row);
        requireRole(row, userId, row.responderUserId(), "Only the responder can decline.");

        requestRepo.decline(requestId);
        notificationDispatcher.dispatchVideoCallDeclined(row.requesterUserId(), requestId,
                row.callType());
        return toView(requestRepo.findById(requestId).orElseThrow(), userId);
    }

    /**
     * Requester re-pings the responder while PENDING — "notify again" button.
     * Cooldown-guarded (single guarded UPDATE); re-emits the type-correct
     * *_CALL_REQUESTED alert so the recipient's ringing UI re-fires.
     */
    @Transactional
    public VideoCallRequestView remind(UUID requestId, UUID userId) {
        VideoCallRequestRow row = lockAndGuard(requestId, userId);
        requireRole(row, userId, row.requesterUserId(),
                "Only the requester can send a reminder.");

        if (!VideoCallConstants.STATUS_PENDING.equals(row.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Reminders can only be sent while the request is pending.");
        }

        Integer reminderCount = requestRepo.tryReminder(requestId,
                props.getVideoCallReminderCooldownSeconds());
        if (reminderCount == null) {
            long cooldown = props.getVideoCallReminderCooldownSeconds();
            // A null result with the row locked means the cooldown guard
            // rejected (status was already verified PENDING above).
            long retryAfter = row.lastRemindedAt() == null ? cooldown
                    : Math.max(1, cooldown
                            - Duration.between(row.lastRemindedAt(), OffsetDateTime.now()).getSeconds());
            throw new ReminderCooldownException(retryAfter);
        }

        notificationDispatcher.dispatchVideoCallRequested(row.responderUserId(),
                requestId, row.callType(), "reminder:" + reminderCount);
        return toView(requestRepo.findById(requestId).orElseThrow(), userId);
    }

    /** Requester withdraws a PENDING request → CANCELLED. */
    @Transactional
    public VideoCallRequestView cancel(UUID requestId, UUID userId) {
        VideoCallRequestRow row = lockAndGuard(requestId, userId);
        requirePending(row);
        requireRole(row, userId, row.requesterUserId(), "Only the requester can cancel.");

        requestRepo.cancel(requestId);
        notificationDispatcher.dispatchVideoCallCancelled(row.responderUserId(), requestId,
                row.callType());
        return toView(requestRepo.findById(requestId).orElseThrow(), userId);
    }

    /**
     * Mints join credentials for a participant on an ACCEPTED request.
     * The requester's FIRST join stamps the charge key and charges the
     * VIDEO_CALL action — stamped first under the row lock so a retry never
     * double-charges. Responder never pays.
     *
     * <p>The FIRST successful join of either party anchors
     * {@code call_deadline_at = now + <per-type cap>}; every join (initial,
     * renew, reconnect) gets a token whose privilege expiry is
     * {@code min(deadline, now + maxTokenTtl)}, so Agora drops both parties
     * at the same instant. A join after the deadline completes the request
     * and fails 409 CALL_DURATION_EXCEEDED — the client's mid-call token
     * renewal dies exactly at the deadline.
     *
     * <p>{@code noRollbackFor}: the COMPLETED transition + time-limit alerts
     * must commit even though the 409 ends the call — otherwise the
     * rejection would silently undo the state change.
     */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public VideoCallProvider.JoinSession join(UUID requestId, UUID userId) {
        VideoCallRequestRow row = lockAndGuard(requestId, userId);
        requireParticipant(row, userId);

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime deadline = row.callDeadlineAt();
        if (deadline != null && now.isAfter(deadline)) {
            // Checked before the ACCEPTED guard so a renewal that lands
            // after the sweeper already completed the request still reports
            // the duration cap, not a generic conflict.
            completeForTimeLimit(row);
            throw new CallDurationExceededException();
        }
        if (!VideoCallConstants.STATUS_ACCEPTED.equals(row.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Video call is not accepted — cannot join.");
        }

        boolean isRequester = userId.equals(row.requesterUserId());
        if (isRequester && row.requesterChargeKey() == null) {
            chargeAndStamp(row, userId);
        }

        if (deadline == null) {
            // First successful join of either party — anchor the shared
            // deadline at now + the per-call-type cap.
            deadline = now.plusSeconds(maxCallSeconds(row.callType()));
            requestRepo.anchorDeadline(requestId, deadline);
        }

        if (isRequester) {
            requestRepo.stampRequesterJoin(requestId);
        } else {
            requestRepo.stampResponderJoin(requestId);
        }

        return videoProvider.issueJoin(requestId, userId, false, deadline);
    }

    /** Either participant ends an ACCEPTED call → COMPLETED (idempotent). */
    @Transactional
    public VideoCallRequestView end(UUID requestId, UUID userId) {
        VideoCallRequestRow row = lockAndGuard(requestId, userId);
        requireParticipant(row, userId);

        if (VideoCallConstants.STATUS_COMPLETED.equals(row.status())) {
            return toView(row, userId);
        }
        if (!VideoCallConstants.STATUS_ACCEPTED.equals(row.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Video call is not in progress.");
        }
        requestRepo.end(requestId);
        return toView(requestRepo.findById(requestId).orElseThrow(), userId);
    }

    /** History + live request for a match — caller must be a participant. */
    @Transactional(readOnly = true)
    public Map<String, Object> listForMatch(UUID matchId, UUID userId, int page, int size) {
        MatchPair match = requestRepo.findMatch(matchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Match not found."));
        if (!userId.equals(match.userOneId()) && !userId.equals(match.userTwoId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied.");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requests", requestRepo.findByMatchId(matchId, page, size)
                .stream().map(r -> toView(r, userId)).toList());
        result.put("total", requestRepo.countByMatchId(matchId));
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    /** Sweeper: expire PENDING requests past their window; notify requester. */
    @Transactional
    public List<UUID> expireSweep() {
        List<VideoCallRequestRow> expired = requestRepo.findExpiredPending();
        return expired.stream()
                .filter(row -> requestRepo.expire(row.id()))
                .peek(row -> {
                    notificationDispatcher.dispatchVideoCallExpired(row.requesterUserId(), row.id(),
                            row.callType());
                    log.info("Video-call request expired: id={}", row.id());
                })
                .map(VideoCallRequestRow::id)
                .toList();
    }

    /**
     * Deadline sweeper (~30 s): ACCEPTED calls whose call_deadline_at has
     * elapsed are completed — Agora already dropped the participants when
     * the token privilege expired; this is state hygiene. Both parties get
     * the *_ENDED_TIME_LIMIT alert so the client renders "time limit
     * reached" rather than a generic ended screen.
     */
    @Transactional
    public List<UUID> deadlineSweep() {
        return requestRepo.findDeadlinePassed().stream()
                .filter(row -> requestRepo.completeTimeLimit(row.id()))
                .peek(row -> {
                    notifyTimeLimitReached(row);
                    log.info("Video-call duration cap reached: id={}", row.id());
                })
                .map(VideoCallRequestRow::id)
                .toList();
    }

    /** Kills all live requests for a match — hook for unmatch / block paths. */
    @Transactional
    public int cancelActiveForMatch(UUID matchId) {
        return requestRepo.cancelActiveForMatch(matchId);
    }

    // ── Private helpers ──────────────────────────────────────────────────

    /**
     * Locks the row, requires participant, then runs the lazy killswitch:
     * match no longer ACTIVE or a block exists → live request is CANCELLED
     * and the caller gets 409.
     */
    private VideoCallRequestRow lockAndGuard(UUID requestId, UUID userId) {
        VideoCallRequestRow row = requestRepo.lockById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Video-call request not found."));
        requireParticipant(row, userId);

        boolean killswitch = requestRepo.blockExists(row.requesterUserId(), row.responderUserId())
                || requestRepo.findMatch(row.matchId())
                        .map(m -> !"ACTIVE".equals(m.status()))
                        .orElse(true);

        if (killswitch) {
            boolean live = VideoCallConstants.STATUS_PENDING.equals(row.status())
                    || VideoCallConstants.STATUS_ACCEPTED.equals(row.status());
            if (live) {
                requestRepo.cancelActive(requestId);
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Video-call request is no longer active.");
        }
        return row;
    }

    /** Normalizes the optional call_type body field — null/blank defaults to VIDEO. */
    private static String resolveCallType(String callType) {
        if (callType == null || callType.isBlank()) {
            return VideoCallConstants.CALL_TYPE_VIDEO;
        }
        String normalized = callType.trim().toUpperCase();
        if (!VideoCallConstants.CALL_TYPE_VIDEO.equals(normalized)
                && !VideoCallConstants.CALL_TYPE_AUDIO.equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Unknown call_type: " + callType);
        }
        return normalized;
    }

    private MatchPair loadActiveParticipantMatch(UUID matchId, UUID userId) {
        MatchPair match = requestRepo.findMatch(matchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Match not found."));
        if (!userId.equals(match.userOneId()) && !userId.equals(match.userTwoId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied.");
        }
        if (!"ACTIVE".equals(match.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Match is no longer active.");
        }
        return match;
    }

    private static void requirePending(VideoCallRequestRow row) {
        if (!VideoCallConstants.STATUS_PENDING.equals(row.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Request is no longer pending.");
        }
    }

    private static void requireParticipant(VideoCallRequestRow row, UUID userId) {
        if (!userId.equals(row.requesterUserId()) && !userId.equals(row.responderUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied.");
        }
    }

    private static void requireRole(VideoCallRequestRow row, UUID userId,
                                    UUID expected, String message) {
        requireParticipant(row, userId);
        if (!userId.equals(expected)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
        }
    }

    /** Max call duration in seconds for the request's call_type. */
    private int maxCallSeconds(String callType) {
        return VideoCallConstants.CALL_TYPE_AUDIO.equals(callType)
                ? videoProps.getCall().getAudioMaxSeconds()
                : videoProps.getCall().getVideoMaxSeconds();
    }

    /** Transitions to COMPLETED (ended_at = deadline) and alerts both parties. */
    private void completeForTimeLimit(VideoCallRequestRow row) {
        if (requestRepo.completeTimeLimit(row.id())) {
            notifyTimeLimitReached(row);
        }
    }

    private void notifyTimeLimitReached(VideoCallRequestRow row) {
        notificationDispatcher.dispatchVideoCallEndedTimeLimit(
                row.requesterUserId(), row.id(), row.callType());
        notificationDispatcher.dispatchVideoCallEndedTimeLimit(
                row.responderUserId(), row.id(), row.callType());
    }

    /**
     * Stamps the charge key first (guarded WHERE charge_key IS NULL), then
     * charges. Under the row lock only one tx wins the stamp; a lost race
     * means another tx already charged — join proceeds free of a second
     * charge. Billing failure leaves charge_key NULL so a later join retries.
     */
    private void chargeAndStamp(VideoCallRequestRow row, UUID userId) {
        UUID chargeKey = UUID.randomUUID();
        if (!requestRepo.stampCharge(row.id(), chargeKey)) {
            return; // already charged by a prior join
        }

        // Billing is per call type — AUDIO_CALL and VIDEO_CALL are priced and
        // ledgered as separate feature actions.
        String actionCode = VideoCallConstants.CALL_TYPE_AUDIO.equals(row.callType())
                ? VideoCallConstants.ACTION_AUDIO_CALL
                : VideoCallConstants.ACTION_VIDEO_CALL;

        ActionCostService.ActionCostResult cost =
                actionCostService.evaluate(userId, actionCode);
        if (cost.ruleId() != null && cost.limitValue() != null) {
            actionLimitRepo.ensureExists(userId, cost.ruleId(), cost.periodStart(), cost.periodEnd());
            boolean ok = actionLimitRepo
                    .tryIncrementUnderLimit(userId, cost.ruleId(), cost.periodStart(), cost.limitValue())
                    .isPresent();
            if (!ok && !cost.requiresCredits()) {
                throw new ActionLimitExceededException(actionCode, cost.periodType());
            }
        } else if (cost.isBlocked()) {
            throw new ActionLimitExceededException(actionCode, cost.periodType());
        }
        if (cost.requiresCredits()) {
            creditService.consumeCredits(userId, cost.creditCost(),
                    actionCode, "vcr:" + chargeKey);
        }
    }

    /** Caller-relative view: role + which actions are currently valid. */
    private VideoCallRequestView toView(VideoCallRequestRow row, UUID callerId) {
        boolean isRequester = callerId.equals(row.requesterUserId());
        boolean pending = VideoCallConstants.STATUS_PENDING.equals(row.status());
        boolean accepted = VideoCallConstants.STATUS_ACCEPTED.equals(row.status());
        // Remind eligibility for the requester: null = not possible, 0 = now.
        Long nextRemindInSeconds = null;
        if (pending && isRequester) {
            long cooldown = props.getVideoCallReminderCooldownSeconds();
            nextRemindInSeconds = row.lastRemindedAt() == null ? 0L
                    : Math.max(0, cooldown
                            - Duration.between(row.lastRemindedAt(), OffsetDateTime.now()).getSeconds());
        }
        return new VideoCallRequestView(
                row.id(), row.matchId(), row.status(), row.callType(), isRequester,
                row.requestExpiresAt(), row.respondedAt(),
                /* canCancel  */ pending && isRequester,
                /* canAccept  */ pending && !isRequester,
                /* canJoin    */ accepted,
                row.requesterJoinedAt() != null,
                row.responderJoinedAt() != null,
                row.callDeadlineAt(),
                row.endedAt(), row.createdAt(),
                nextRemindInSeconds);
    }

    /**
     * 409 when a join/renewal arrives after call_deadline_at — carries the
     * {@code CALL_DURATION_EXCEEDED} code (the reason IS the code;
     * GlobalExceptionHandler passes machine-shaped reasons through
     * unchanged) so clients distinguish it from "not ACCEPTED" conflicts.
     */
    public static class CallDurationExceededException extends ResponseStatusException {
        public CallDurationExceededException() {
            super(HttpStatus.CONFLICT, "CALL_DURATION_EXCEEDED");
        }
    }

    /**
     * 429 while the remind cooldown is running — carries the seconds until
     * the next remind is allowed. GlobalExceptionHandler turns it into a
     * {@code Retry-After} header + {@code retry_after_seconds} in the body.
     */
    public static class ReminderCooldownException extends ResponseStatusException {
        private final long retryAfterSeconds;

        public ReminderCooldownException(long retryAfterSeconds) {
            super(HttpStatus.TOO_MANY_REQUESTS,
                    "Reminder sent too recently — retry after " + retryAfterSeconds + " seconds.");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long getRetryAfterSeconds() {
            return retryAfterSeconds;
        }
    }
}
