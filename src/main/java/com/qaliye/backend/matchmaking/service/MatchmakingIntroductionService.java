package com.qaliye.backend.matchmaking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaliye.backend.matchmaking.MatchmakingConstants;
import com.qaliye.backend.matchmaking.config.MatchmakingProperties;
import com.qaliye.backend.matchmaking.dto.IntroductionSummary;
import com.qaliye.backend.matchmaking.dto.IntroductionView;
import com.qaliye.backend.matchmaking.repository.MatchmakingIntroductionRepository;
import com.qaliye.backend.matchmaking.repository.MatchmakingIntroductionRepository.IntroductionRow;
import com.qaliye.backend.matchmaking.repository.MatchmakingIntroductionRepository.ParticipantRow;
import com.qaliye.backend.matchmaking.repository.MatchmakingPreferenceRepository;
import com.qaliye.backend.matchmaking.repository.MatchmakingPreferenceRepository.PreferenceRow;
import com.qaliye.backend.matchmaking.repository.MatchmakingRequestRepository;
import com.qaliye.backend.matchmaking.repository.MatchmakingRequestRepository.RequestRow;
import com.qaliye.backend.matchmaking.service.MatchmakingCompatibilityService.CompatibilityResult;
import com.qaliye.backend.matchmaking.service.MatchmakingMatchService.MatchResult;
import com.qaliye.backend.notifications.NotificationDispatcher;
import com.qaliye.backend.profile.ProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Lifecycle management for matchmaking introductions.
 *
 * An introduction pairs two OPEN requests. When both sides decide INTERESTED
 * a match is created directly (no MUTUAL_INTEREST intermediate state). When
 * either side decides NOT_INTERESTED the introduction is DECLINED and each
 * request is settled individually: quota-exhausted requests → COMPLETED,
 * others → OPEN.
 */
@Service
public class MatchmakingIntroductionService {

    private static final Logger log = LoggerFactory.getLogger(MatchmakingIntroductionService.class);

    private final MatchmakingIntroductionRepository introductionRepo;
    private final MatchmakingRequestRepository requestRepo;
    private final MatchmakingPreferenceRepository preferenceRepo;
    private final MatchmakingMatchService matchService;
    private final MatchmakingCompatibilityService compatibilityService;
    private final MatchmakingUserFactsLoader factsLoader;
    private final MatchmakingProperties props;
    private final NotificationDispatcher notificationDispatcher;
    private final ProfileService profileService;
    private final ObjectMapper objectMapper;

    public MatchmakingIntroductionService(MatchmakingIntroductionRepository introductionRepo,
                                          MatchmakingRequestRepository requestRepo,
                                          MatchmakingPreferenceRepository preferenceRepo,
                                          MatchmakingMatchService matchService,
                                          MatchmakingCompatibilityService compatibilityService,
                                          MatchmakingUserFactsLoader factsLoader,
                                          MatchmakingProperties props,
                                          NotificationDispatcher notificationDispatcher,
                                          ProfileService profileService,
                                          ObjectMapper objectMapper) {
        this.introductionRepo = introductionRepo;
        this.requestRepo = requestRepo;
        this.preferenceRepo = preferenceRepo;
        this.matchService = matchService;
        this.compatibilityService = compatibilityService;
        this.factsLoader = factsLoader;
        this.props = props;
        this.notificationDispatcher = notificationDispatcher;
        this.profileService = profileService;
        this.objectMapper = objectMapper;
    }

    /**
     * Admin creates an introduction between two OPEN requests.
     *
     * <p>Compatibility is scored internally from the live preference rows +
     * resolved profile facts of both users; percentages, match_details and a
     * generated reason are persisted so the rationale survives preference
     * changes (design §12). The admin-supplied {@code reason}, when present,
     * overrides the generated one.
     *
     * @param requesterRequestId  the requester's request
     * @param candidateRequestId  the candidate's request
     * @param adminUserId         the admin creating the introduction
     * @param reason              optional admin reason (nullable — generated
     *                            from the score details when blank)
     * @return the newly created introduction
     */
    @Transactional
    public IntroductionRow createIntroduction(UUID requesterRequestId, UUID candidateRequestId,
                                              UUID adminUserId, String reason) {
        if (requesterRequestId.equals(candidateRequestId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Requester and candidate requests must differ.");
        }

        RequestRow requesterRequest = assertRequestOpen(requesterRequestId);
        RequestRow candidateRequest = assertRequestOpen(candidateRequestId);
        assertQuotaAvailable(requesterRequestId);
        assertQuotaAvailable(candidateRequestId);

        CompatibilityResult score = compatibilityService.score(
                MatchmakingCompatibilityService.side(
                        loadPrefs(requesterRequest), loadFacts(requesterRequest.userId())),
                MatchmakingCompatibilityService.side(
                        loadPrefs(candidateRequest), loadFacts(candidateRequest.userId())));

        OffsetDateTime decisionExpiresAt = OffsetDateTime.now()
                .plusHours(props.getIntroductionDecisionWindowHours());

        String reasonToStore = (reason != null && !reason.isBlank())
                ? reason : score.reason();

        UUID introductionId = introductionRepo.insert(
                requesterRequestId, candidateRequestId, adminUserId,
                score.forwardPercentage(), score.reversePercentage(),
                score.overallPercentage(), toJson(score.details()),
                reasonToStore, decisionExpiresAt);

        if (requestRepo.setOnHold(requesterRequestId, candidateRequestId) != 2) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "One or both requests are no longer OPEN.");
        }

        IntroductionRow intro = introductionRepo.findById(introductionId).orElseThrow();

        notificationDispatcher.dispatchMatchmakingIntroductionProposedNotification(
                requesterRequest.userId(), candidateRequest.userId(), introductionId,
                requesterRequestId, candidateRequestId);

        log.info("Introduction created: id={}, requester={}, candidate={}", introductionId,
                requesterRequestId, candidateRequestId);
        return intro;
    }

    /** User records their decision (INTERESTED or NOT_INTERESTED) for an introduction. */
    @Transactional
    public IntroductionRow recordDecision(UUID introductionId, UUID userId, String decision) {
        validateDecision(decision);
        var intro = introductionRepo.findByIdForUpdate(introductionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Introduction not found."));

        if (!MatchmakingConstants.INTRO_PROPOSED.equals(intro.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Introduction is no longer awaiting decisions.");
        }

        var requesterRequest = requestRepo.findById(intro.requesterRequestId()).orElseThrow();
        var candidateRequest = requestRepo.findById(intro.candidateRequestId()).orElseThrow();

        boolean isRequester = userId.equals(requesterRequest.userId());
        boolean isCandidate = userId.equals(candidateRequest.userId());
        if (!isRequester && !isCandidate) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied.");
        }

        String existing = isRequester ? intro.requesterDecision() : intro.candidateDecision();
        if (!MatchmakingConstants.DECISION_PENDING.equals(existing)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Decision already submitted.");
        }

        if (isRequester) {
            introductionRepo.setRequesterDecision(introductionId, decision);
        } else {
            introductionRepo.setCandidateDecision(introductionId, decision);
        }

        var updated = introductionRepo.findById(introductionId).orElseThrow();
        return finalizeIfComplete(updated, requesterRequest, candidateRequest);
    }

    /** Admin cancels an introduction. Both requests revert to OPEN (quota not consumed). */
    @Transactional
    public void cancelIntroduction(UUID introductionId) {
        var intro = introductionRepo.findByIdForUpdate(introductionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Introduction not found."));
        boolean cancelled = introductionRepo.cancel(introductionId);
        if (!cancelled) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Introduction cannot be cancelled in its current state.");
        }
        requestRepo.releaseOnHold(intro.requesterRequestId(), intro.candidateRequestId());
        dispatchIntroductionEnded(intro, MatchmakingConstants.INTRO_CANCELLED);
    }

    /** Sweeper: expire PROPOSED introductions past their decision window. */
    @Transactional
    public List<UUID> expireIntroductions() {
        List<IntroductionRow> expired = introductionRepo.findExpiredProposed();
        List<UUID> expiredIds = new ArrayList<>();
        for (IntroductionRow intro : expired) {
            boolean done = introductionRepo.expire(intro.id());
            if (done) {
                var rReq = requestRepo.findById(intro.requesterRequestId()).orElse(null);
                var cReq = requestRepo.findById(intro.candidateRequestId()).orElse(null);
                if (rReq != null) settleRequest(rReq);
                if (cReq != null) settleRequest(cReq);
                if (rReq != null && cReq != null) {
                    notificationDispatcher.dispatchMatchmakingIntroductionExpiredNotification(
                            rReq.userId(), cReq.userId(), intro.id(),
                            rReq.id(), cReq.id());
                }
                expiredIds.add(intro.id());
                log.info("Introduction expired: id={}", intro.id());
            }
        }
        return expiredIds;
    }

    @Transactional(readOnly = true)
    public IntroductionRow getIntroduction(UUID introductionId, UUID callerId) {
        var intro = introductionRepo.findById(introductionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Introduction not found."));
        assertParticipant(intro, callerId);
        return intro;
    }

    /**
     * User-facing view: the introduction plus the proposed partner's full profile
     * and caller-relative role/decision fields. This is what the decision screen renders.
     */
    @Transactional(readOnly = true)
    public IntroductionView getIntroductionView(UUID introductionId, UUID callerId) {
        var intro = introductionRepo.findById(introductionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Introduction not found."));
        var requesterReq = requestRepo.findById(intro.requesterRequestId()).orElseThrow();
        var candidateReq = requestRepo.findById(intro.candidateRequestId()).orElseThrow();

        boolean isRequester = callerId.equals(requesterReq.userId());
        boolean isCandidate = callerId.equals(candidateReq.userId());
        if (!isRequester && !isCandidate) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied.");
        }

        UUID partnerUserId = isRequester ? candidateReq.userId() : requesterReq.userId();
        var partner = profileService.getMatchmakingPartnerProfile(callerId, partnerUserId);

        int callerForward  = isRequester ? intro.forwardPercentage() : intro.reversePercentage();
        int callerReverse  = isRequester ? intro.reversePercentage() : intro.forwardPercentage();

        String yourDecision = isRequester ? intro.requesterDecision() : intro.candidateDecision();
        String partnerDecision = maskPartnerDecision(yourDecision,
                isRequester ? intro.candidateDecision() : intro.requesterDecision());

        return new IntroductionView(
                intro.id(), intro.requesterRequestId(), intro.candidateRequestId(),
                isRequester ? "REQUESTER" : "CANDIDATE",
                isRequester ? requesterReq.id() : candidateReq.id(),
                yourDecision,
                partnerDecision,
                intro.status(),
                callerForward, callerReverse, intro.overallPercentage(),
                intro.decisionExpiresAt(),
                intro.requesterDecidedAt(), intro.candidateDecidedAt(),
                intro.matchId(), intro.proposedAt(),
                intro.completedAt(), intro.createdAt(), intro.updatedAt(),
                partner);
    }

    @Transactional(readOnly = true)
    public IntroductionRow getIntroductionAdmin(UUID introductionId) {
        return introductionRepo.findById(introductionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Introduction not found."));
    }

    private static final Set<String> INTRO_STATUSES = Set.of(
            MatchmakingConstants.INTRO_PROPOSED,
            MatchmakingConstants.INTRO_DECLINED,
            MatchmakingConstants.INTRO_MATCHED,
            MatchmakingConstants.INTRO_CANCELLED,
            MatchmakingConstants.INTRO_EXPIRED);

    /**
     * Admin dashboard: global introductions list, newest first.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> listIntroductionsAdmin(String status, UUID userId,
                                                      String search, int page, int size) {
        String normalizedStatus = (status != null && !status.isBlank())
                ? status.trim().toUpperCase() : null;
        if (normalizedStatus != null && !INTRO_STATUSES.contains(normalizedStatus)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Unknown introduction status: " + status);
        }
        String searchPattern = (search != null && !search.isBlank())
                ? "%" + search.trim() + "%" : null;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("introductions",
                introductionRepo.findAll(normalizedStatus, userId, searchPattern, page, size));
        result.put("total",
                introductionRepo.countAll(normalizedStatus, userId, searchPattern));
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    @Transactional(readOnly = true)
    public List<IntroductionRow> getIntroductionsForRequest(UUID requestId) {
        return introductionRepo.findByRequestId(requestId);
    }

    /**
     * Caller's introduction history, newest proposal first. Each row is
     * resolved relative to the caller (role / your_decision / partner) and
     * the partner's decision is masked while the caller has not decided.
     */
    @Transactional(readOnly = true)
    public List<IntroductionSummary> getMyIntroductions(UUID callerId, int page, int size) {
        List<IntroductionSummary> summaries = new ArrayList<>();
        for (ParticipantRow row : introductionRepo.findByParticipantUserId(callerId, page, size)) {
            boolean isRequester = callerId.equals(row.requesterUserId());
            UUID partnerUserId = isRequester ? row.candidateUserId() : row.requesterUserId();

            var partnerProfile = profileService.getMatchmakingPartnerProfile(callerId, partnerUserId);
            var partner = new IntroductionSummary.PartnerSummary(
                    partnerProfile.displayName(), partnerProfile.age(),
                    partnerProfile.primaryPhotoUrl());

            String yourDecision = isRequester ? row.requesterDecision() : row.candidateDecision();
            String partnerDecision = maskPartnerDecision(yourDecision,
                    isRequester ? row.candidateDecision() : row.requesterDecision());

            summaries.add(new IntroductionSummary(
                    row.id(),
                    isRequester ? row.requesterRequestId() : row.candidateRequestId(),
                    isRequester ? "REQUESTER" : "CANDIDATE",
                    yourDecision,
                    partnerDecision,
                    row.status(),
                    row.overallPercentage(),
                    row.decisionExpiresAt(),
                    row.matchId(),
                    row.proposedAt(),
                    row.createdAt(),
                    partner));
        }
        return summaries;
    }

    // ── Private helpers ──────────────────────────────────────────────────

    /**
     * Privacy rule: the partner's decision must not be visible until the
     * caller has recorded their own — otherwise one side could wait to see
     * the other's choice before deciding.
     */
    private static String maskPartnerDecision(String yourDecision, String partnerDecision) {
        return MatchmakingConstants.DECISION_PENDING.equals(yourDecision)
                ? MatchmakingConstants.DECISION_PENDING
                : partnerDecision;
    }

    /**
     * Notifies both participants that the introduction ended for an
     * administrative reason (CANCELLED). Each recipient's payload carries
     * their own request_id.
     */
    private void dispatchIntroductionEnded(IntroductionRow intro, String outcome) {
        var rReq = requestRepo.findById(intro.requesterRequestId()).orElse(null);
        var cReq = requestRepo.findById(intro.candidateRequestId()).orElse(null);
        if (rReq == null || cReq == null) {
            return;
        }
        if (MatchmakingConstants.INTRO_CANCELLED.equals(outcome)) {
            notificationDispatcher.dispatchMatchmakingIntroductionCancelledNotification(
                    rReq.userId(), cReq.userId(), intro.id(), rReq.id(), cReq.id());
        }
    }

    private IntroductionRow finalizeIfComplete(IntroductionRow intro, RequestRow requesterRequest,
                                               RequestRow candidateRequest) {
        String rDec = intro.requesterDecision();
        String cDec = intro.candidateDecision();

        boolean anyDecline = MatchmakingConstants.DECISION_NOT_INTERESTED.equals(rDec)
                || MatchmakingConstants.DECISION_NOT_INTERESTED.equals(cDec);
        boolean bothInterested = MatchmakingConstants.DECISION_INTERESTED.equals(rDec)
                && MatchmakingConstants.DECISION_INTERESTED.equals(cDec);

        if (anyDecline) {
            introductionRepo.transitionStatus(intro.id(),
                    MatchmakingConstants.INTRO_PROPOSED, MatchmakingConstants.INTRO_DECLINED);
            settleRequest(requesterRequest);
            settleRequest(candidateRequest);
            notificationDispatcher.dispatchMatchmakingIntroductionDeclinedNotification(
                    requesterRequest.userId(), candidateRequest.userId(), intro.id(),
                    requesterRequest.id(), candidateRequest.id());

        } else if (bothInterested) {
            MatchResult matchResult = matchService.createMatchmakingMatch(intro.id());
            if (!matchResult.matched()) {
                introductionRepo.transitionStatus(intro.id(),
                        MatchmakingConstants.INTRO_PROPOSED, MatchmakingConstants.INTRO_CANCELLED);
                dispatchIntroductionEnded(intro, MatchmakingConstants.INTRO_CANCELLED);
                if (matchResult.matchId() != null) {
                    // Already matched with this person — complete both requests
                    requestRepo.complete(requesterRequest.id());
                    requestRepo.complete(candidateRequest.id());
                } else {
                    // Block or error — release both back to OPEN
                    requestRepo.releaseOnHold(intro.requesterRequestId(), intro.candidateRequestId());
                }
            }
        }

        return introductionRepo.findById(intro.id()).orElseThrow();
    }

    /**
     * Settles a single ON_HOLD request after its introduction is consumed.
     * Quota-exhausted → COMPLETED; still has remaining quota → OPEN.
     */
    private void settleRequest(RequestRow request) {
        int consumed = introductionRepo.countConsumedByRequest(request.id());
        if (consumed >= props.getIntroductionsPerRequest()) {
            requestRepo.complete(request.id());
        } else {
            requestRepo.updateStatus(request.id(),
                    MatchmakingConstants.REQUEST_ON_HOLD, MatchmakingConstants.REQUEST_OPEN);
        }
    }

    private RequestRow assertRequestOpen(UUID requestId) {
        var request = requestRepo.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Request not found: " + requestId));
        if (!MatchmakingConstants.REQUEST_OPEN.equals(request.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Request " + requestId + " is not OPEN.");
        }
        return request;
    }

    private PreferenceRow loadPrefs(RequestRow request) {
        return preferenceRepo.findById(request.matchmakingPreferencesId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Matchmaking preferences not found for request " + request.id()));
    }

    private MatchmakingCompatibilityService.ProfileFacts loadFacts(UUID userId) {
        return factsLoader.load(userId).toProfileFacts();
    }

    private String toJson(Object details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private void assertQuotaAvailable(UUID requestId) {
        int consumed = introductionRepo.countConsumedByRequest(requestId);
        if (consumed >= props.getIntroductionsPerRequest()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Request " + requestId + " has exhausted its introduction quota.");
        }
    }

    private void assertParticipant(IntroductionRow intro, UUID userId) {
        var req  = requestRepo.findById(intro.requesterRequestId()).orElse(null);
        var cand = requestRepo.findById(intro.candidateRequestId()).orElse(null);
        boolean isParticipant = (req  != null && userId.equals(req.userId()))
                             || (cand != null && userId.equals(cand.userId()));
        if (!isParticipant) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied.");
        }
    }

    private void validateDecision(String decision) {
        if (!MatchmakingConstants.DECISION_INTERESTED.equals(decision)
                && !MatchmakingConstants.DECISION_NOT_INTERESTED.equals(decision)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Decision must be INTERESTED or NOT_INTERESTED.");
        }
    }
}
