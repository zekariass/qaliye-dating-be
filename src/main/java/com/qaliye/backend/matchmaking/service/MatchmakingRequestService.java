package com.qaliye.backend.matchmaking.service;

import com.qaliye.backend.billing.repository.ActionLimitRepository;
import com.qaliye.backend.billing.service.ActionCostService;
import com.qaliye.backend.billing.service.CreditService;
import com.qaliye.backend.discovery.exception.ActionLimitExceededException;
import com.qaliye.backend.matchmaking.MatchmakingConstants;
import com.qaliye.backend.matchmaking.config.MatchmakingProperties;
import com.qaliye.backend.matchmaking.repository.MatchmakingIntroductionRepository;
import com.qaliye.backend.matchmaking.repository.MatchmakingPreferenceRepository;
import com.qaliye.backend.matchmaking.repository.MatchmakingRequestRepository;
import com.qaliye.backend.matchmaking.repository.MatchmakingRequestRepository.RequestRow;
import com.qaliye.backend.notifications.NotificationDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Lifecycle management for matchmaking requests.
 *
 * Creating a request charges only the request fee. No escrow; no snapshot;
 * no greeting message. Preferences are read live from matchmaking_preferences.
 */
@Service
public class MatchmakingRequestService {

    private static final Logger log = LoggerFactory.getLogger(MatchmakingRequestService.class);

    private final MatchmakingRequestRepository requestRepo;
    private final MatchmakingPreferenceRepository preferenceRepo;
    private final MatchmakingIntroductionRepository introductionRepo;
    private final ActionCostService actionCostService;
    private final ActionLimitRepository actionLimitRepo;
    private final CreditService creditService;
    private final NotificationDispatcher notificationDispatcher;
    private final MatchmakingProperties props;

    public MatchmakingRequestService(MatchmakingRequestRepository requestRepo,
                                     MatchmakingPreferenceRepository preferenceRepo,
                                     MatchmakingIntroductionRepository introductionRepo,
                                     ActionCostService actionCostService,
                                     ActionLimitRepository actionLimitRepo,
                                     CreditService creditService,
                                     NotificationDispatcher notificationDispatcher,
                                     MatchmakingProperties props) {
        this.requestRepo = requestRepo;
        this.preferenceRepo = preferenceRepo;
        this.introductionRepo = introductionRepo;
        this.actionCostService = actionCostService;
        this.actionLimitRepo = actionLimitRepo;
        this.creditService = creditService;
        this.notificationDispatcher = notificationDispatcher;
        this.props = props;
    }

    /**
     * Creates a new matchmaking request. Idempotent on chargeIdempotencyKey.
     *
     * @param userId               the requesting user
     * @param chargeIdempotencyKey stable key for the request fee charge
     * @return the created (or existing idempotent) request row
     */
    @Transactional
    public RequestRow createRequest(UUID userId, UUID chargeIdempotencyKey) {
        var existing = requestRepo.findByChargeKey(chargeIdempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        preferenceRepo.refreshGender(userId);
        var preferences = preferenceRepo.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Set matchmaking preferences before submitting a request."));

        var activeRequest = requestRepo.findActiveByUserId(userId);
        if (activeRequest.isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "You already have an active matchmaking request.");
        }

        chargeRequestFee(userId, chargeIdempotencyKey);

        OffsetDateTime expiresAt = OffsetDateTime.now().plusDays(props.getRequestLifetimeDays());

        UUID requestId;
        try {
            requestId = requestRepo.insert(userId, preferences.id(), chargeIdempotencyKey, expiresAt);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "You already have an active matchmaking request.");
        }

        notificationDispatcher.dispatchMatchmakingRequestCreated(userId, requestId);
        log.info("Matchmaking request created: id={}, user={}", requestId, userId);
        return requestRepo.findById(requestId)
                .orElseThrow(() -> new IllegalStateException("Request not found after insert"));
    }

    /** Returns all requests for the caller (history), most-recent first. */
    @Transactional(readOnly = true)
    public List<RequestRow> getMyRequests(UUID userId, int page, int size) {
        return requestRepo.findByUserId(userId, page, size);
    }

    /** Returns the caller's single active request, or 404. */
    @Transactional(readOnly = true)
    public RequestRow getActiveRequest(UUID userId) {
        return requestRepo.findActiveByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No active matchmaking request found."));
    }

    /** Returns any request by ID, enforcing that the caller is the owner. */
    @Transactional(readOnly = true)
    public RequestRow getRequest(UUID requestId, UUID callerId) {
        var request = requestRepo.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found."));
        if (!request.userId().equals(callerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied.");
        }
        return request;
    }

    /** Admin: returns any request by ID without ownership check. */
    @Transactional(readOnly = true)
    public RequestRow getRequestAdmin(UUID requestId) {
        return requestRepo.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found."));
    }

    /** Cancels an OPEN or ON_HOLD request. Only the owner can cancel. */
    @Transactional
    public void cancelRequest(UUID requestId, UUID callerId) {
        var request = requestRepo.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found."));
        if (!request.userId().equals(callerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied.");
        }
        boolean cancelled = requestRepo.cancel(requestId);
        if (!cancelled) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Request cannot be cancelled in its current state.");
        }
        cancelActiveIntroduction(requestId);
        notificationDispatcher.dispatchMatchmakingRequestCancelledNotification(
                request.userId(), requestId);
    }

    /** Admin: cancels any request without ownership check. */
    @Transactional
    public void cancelRequestAdmin(UUID requestId) {
        var request = requestRepo.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found."));
        boolean cancelled = requestRepo.cancel(requestId);
        if (!cancelled) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Request cannot be cancelled in its current state.");
        }
        cancelActiveIntroduction(requestId);
        notificationDispatcher.dispatchMatchmakingRequestCancelledNotification(
                request.userId(), requestId);
    }

    /**
     * Cancels the active introduction so the partner's ON_HOLD request is
     * released back to OPEN instead of dangling, and notifies both
     * participants that the introduction was cancelled.
     */
    private void cancelActiveIntroduction(UUID requestId) {
        introductionRepo.findActiveByRequestId(requestId).ifPresent(intro -> {
            introductionRepo.cancel(intro.id());
            requestRepo.releaseOnHold(intro.requesterRequestId(), intro.candidateRequestId());
            var rReq = requestRepo.findById(intro.requesterRequestId()).orElse(null);
            var cReq = requestRepo.findById(intro.candidateRequestId()).orElse(null);
            if (rReq != null && cReq != null) {
                notificationDispatcher.dispatchMatchmakingIntroductionCancelledNotification(
                        rReq.userId(), cReq.userId(), intro.id(), rReq.id(), cReq.id());
            }
        });
    }

    /** Sweeper: bulk-expire OPEN requests past their expiry time. */
    @Transactional
    public List<UUID> expireOpenRequests() {
        return requestRepo.expireOpenRequests();
    }

    /**
     * Charges the PERSONAL_MATCHMAKING_REQUEST fee — free under a rate limit,
     * credits otherwise. Idempotent via the charge key; runs inside the create
     * transaction so a request is never OPEN and unpaid (invariant §15.1).
     */
    private void chargeRequestFee(UUID userId, UUID chargeIdempotencyKey) {
        ActionCostService.ActionCostResult cost =
                actionCostService.evaluate(userId, MatchmakingConstants.ACTION_REQUEST);
        if (cost.ruleId() != null && cost.limitValue() != null) {
            actionLimitRepo.ensureExists(userId, cost.ruleId(), cost.periodStart(), cost.periodEnd());
            boolean ok = actionLimitRepo
                    .tryIncrementUnderLimit(userId, cost.ruleId(), cost.periodStart(), cost.limitValue())
                    .isPresent();
            if (!ok && !cost.requiresCredits()) {
                throw new ActionLimitExceededException(
                        MatchmakingConstants.ACTION_REQUEST, cost.periodType());
            }
        } else if (cost.isBlocked()) {
            throw new ActionLimitExceededException(
                    MatchmakingConstants.ACTION_REQUEST, cost.periodType());
        }
        if (cost.requiresCredits()) {
            creditService.consumeCredits(userId, cost.creditCost(),
                    MatchmakingConstants.ACTION_REQUEST, "mm-req:" + chargeIdempotencyKey);
        }
    }

    private static final Set<String> REQUEST_STATUSES = Set.of(
            MatchmakingConstants.REQUEST_OPEN,
            MatchmakingConstants.REQUEST_ON_HOLD,
            MatchmakingConstants.REQUEST_MATCHED,
            MatchmakingConstants.REQUEST_COMPLETED,
            MatchmakingConstants.REQUEST_CANCELLED,
            MatchmakingConstants.REQUEST_EXPIRED);

    /**
     * Admin queue: all requests when {@code status} is null/blank, else a single status.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getQueue(String status, int page, int size) {
        String normalized = (status != null && !status.isBlank())
                ? status.trim().toUpperCase() : null;
        if (normalized != null && !REQUEST_STATUSES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Unknown request status: " + status);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requests", requestRepo.findQueue(normalized, page, size));
        result.put("total", requestRepo.countQueue(normalized));
        result.put("page", page);
        result.put("size", size);
        return result;
    }
}
