package com.qaliye.backend.matchmaking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaliye.backend.matchmaking.MatchmakingConstants;
import com.qaliye.backend.matchmaking.config.MatchmakingProperties;
import com.qaliye.backend.matchmaking.repository.MatchmakingIntroductionRepository;
import com.qaliye.backend.matchmaking.repository.MatchmakingIntroductionRepository.IntroductionRow;
import com.qaliye.backend.matchmaking.repository.MatchmakingPreferenceRepository;
import com.qaliye.backend.matchmaking.repository.MatchmakingPreferenceRepository.PreferenceRow;
import com.qaliye.backend.matchmaking.repository.MatchmakingRequestRepository;
import com.qaliye.backend.matchmaking.repository.MatchmakingRequestRepository.RequestRow;
import com.qaliye.backend.matchmaking.service.MatchmakingCompatibilityService.CompatibilityResult;
import com.qaliye.backend.matchmaking.service.MatchmakingMatchService.MatchResult;
import com.qaliye.backend.matchmaking.service.MatchmakingUserFactsLoader.FactRow;
import com.qaliye.backend.notifications.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MatchmakingIntroductionServiceTest {

    @Mock MatchmakingIntroductionRepository introductionRepo;
    @Mock MatchmakingRequestRepository requestRepo;
    @Mock MatchmakingPreferenceRepository preferenceRepo;
    @Mock MatchmakingMatchService matchService;
    @Mock MatchmakingCompatibilityService compatibilityService;
    @Mock MatchmakingUserFactsLoader factsLoader;
    @Mock NotificationDispatcher notificationDispatcher;
    @Mock com.qaliye.backend.profile.ProfileService profileService;

    MatchmakingProperties props = new MatchmakingProperties();
    ObjectMapper objectMapper = new ObjectMapper();
    MatchmakingIntroductionService service;

    UUID requesterUserId = UUID.randomUUID();
    UUID candidateUserId = UUID.randomUUID();
    UUID adminUserId = UUID.randomUUID();
    UUID requesterRequestId = UUID.randomUUID();
    UUID candidateRequestId = UUID.randomUUID();
    UUID introductionId = UUID.randomUUID();

    UUID requesterPrefId = UUID.randomUUID();
    UUID candidatePrefId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new MatchmakingIntroductionService(introductionRepo, requestRepo,
                preferenceRepo, matchService, compatibilityService, factsLoader,
                props, notificationDispatcher, profileService, objectMapper);
    }

    private FactRow facts() {
        return new FactRow("MALE", 30, 175, "ET",
                false, true, null, null, null, null, null,
                new UUID[0], new UUID[0]);
    }

    /** Stubs everything createIntroduction needs after the OPEN checks. */
    private void stubScoring() {
        when(preferenceRepo.findById(any())).thenReturn(Optional.of(prefRow(requesterPrefId)));
        when(factsLoader.load(any())).thenReturn(facts());
        when(compatibilityService.score(any(), any()))
                .thenReturn(new CompatibilityResult(80, 70, 75, List.of(), "compatible"));
    }

    // ── createIntroduction ────────────────────────────────────────────────

    @Test
    void createIntroduction_requesterNotOpen_throws409() {
        when(requestRepo.findById(requesterRequestId))
                .thenReturn(Optional.of(requestRow(requesterRequestId, requesterUserId,
                        MatchmakingConstants.REQUEST_ON_HOLD)));

        assertThatThrownBy(() -> service.createIntroduction(
                requesterRequestId, candidateRequestId, adminUserId, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not OPEN");
    }

    @Test
    void createIntroduction_sameRequestId_throws400() {
        assertThatThrownBy(() -> service.createIntroduction(
                requesterRequestId, requesterRequestId, adminUserId, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("differ");
    }

    @Test
    void createIntroduction_success_setsOnHoldAndNotifies() {
        var requesterReq = openRequestRow(requesterRequestId, requesterUserId);
        var candidateReq = openRequestRow(candidateRequestId, candidateUserId);

        when(requestRepo.findById(requesterRequestId))
                .thenReturn(Optional.of(requesterReq))
                .thenReturn(Optional.of(requesterReq));
        when(requestRepo.findById(candidateRequestId))
                .thenReturn(Optional.of(candidateReq))
                .thenReturn(Optional.of(candidateReq));
        stubScoring();
        when(introductionRepo.insert(any(), any(), any(),
                anyInt(), anyInt(), anyInt(), any(), any(), any()))
                .thenReturn(introductionId);
        when(requestRepo.setOnHold(requesterRequestId, candidateRequestId)).thenReturn(2);
        when(introductionRepo.findById(introductionId))
                .thenReturn(Optional.of(proposedIntroRow(introductionId)));

        var result = service.createIntroduction(requesterRequestId, candidateRequestId,
                adminUserId, null);

        assertThat(result.id()).isEqualTo(introductionId);
        verify(requestRepo).setOnHold(requesterRequestId, candidateRequestId);
        verify(compatibilityService).score(any(), any());
        verify(notificationDispatcher).dispatchMatchmakingIntroductionProposedNotification(
                eq(requesterUserId), eq(candidateUserId), eq(introductionId),
                eq(requesterRequestId), eq(candidateRequestId));
    }

    @Test
    void createIntroduction_holdClaimFails_throws409() {
        var requesterReq = openRequestRow(requesterRequestId, requesterUserId);
        var candidateReq = openRequestRow(candidateRequestId, candidateUserId);

        when(requestRepo.findById(requesterRequestId)).thenReturn(Optional.of(requesterReq));
        when(requestRepo.findById(candidateRequestId)).thenReturn(Optional.of(candidateReq));
        stubScoring();
        when(introductionRepo.insert(any(), any(), any(),
                anyInt(), anyInt(), anyInt(), any(), any(), any()))
                .thenReturn(introductionId);
        // Only one row still OPEN — a concurrent introduction claimed the other.
        when(requestRepo.setOnHold(requesterRequestId, candidateRequestId)).thenReturn(1);

        assertThatThrownBy(() -> service.createIntroduction(
                requesterRequestId, candidateRequestId, adminUserId, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no longer OPEN");
    }

    // ── recordDecision ────────────────────────────────────────────────────

    @Test
    void recordDecision_introductionNotFound_throws404() {
        when(introductionRepo.findByIdForUpdate(introductionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordDecision(introductionId, requesterUserId,
                MatchmakingConstants.DECISION_INTERESTED))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void recordDecision_notParticipant_throws403() {
        var intro = proposedIntroRow(introductionId);
        when(introductionRepo.findByIdForUpdate(introductionId)).thenReturn(Optional.of(intro));
        when(requestRepo.findById(requesterRequestId))
                .thenReturn(Optional.of(openRequestRow(requesterRequestId, requesterUserId)));
        when(requestRepo.findById(candidateRequestId))
                .thenReturn(Optional.of(openRequestRow(candidateRequestId, candidateUserId)));

        UUID stranger = UUID.randomUUID();
        assertThatThrownBy(() -> service.recordDecision(introductionId, stranger,
                MatchmakingConstants.DECISION_INTERESTED))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Access denied");
    }

    @Test
    void recordDecision_requesterInterested_onlyOneDecision_returnsProposed() {
        var intro = proposedIntroRow(introductionId);
        when(introductionRepo.findByIdForUpdate(introductionId)).thenReturn(Optional.of(intro));
        when(requestRepo.findById(requesterRequestId))
                .thenReturn(Optional.of(openRequestRow(requesterRequestId, requesterUserId)));
        when(requestRepo.findById(candidateRequestId))
                .thenReturn(Optional.of(openRequestRow(candidateRequestId, candidateUserId)));
        var updatedIntro = introRowWithDecisions(introductionId,
                MatchmakingConstants.DECISION_INTERESTED,
                MatchmakingConstants.DECISION_PENDING, MatchmakingConstants.INTRO_PROPOSED);
        when(introductionRepo.findById(introductionId))
                .thenReturn(Optional.of(updatedIntro));

        var result = service.recordDecision(introductionId, requesterUserId,
                MatchmakingConstants.DECISION_INTERESTED);

        assertThat(result.status()).isEqualTo(MatchmakingConstants.INTRO_PROPOSED);
        verify(introductionRepo).setRequesterDecision(introductionId,
                MatchmakingConstants.DECISION_INTERESTED);
        verify(matchService, never()).createMatchmakingMatch(any());
    }

    @Test
    void recordDecision_bothInterested_createsMatch() {
        // Requester already decided INTERESTED — candidate is deciding now.
        var intro = introRowWithDecisions(introductionId,
                MatchmakingConstants.DECISION_INTERESTED,
                MatchmakingConstants.DECISION_PENDING, MatchmakingConstants.INTRO_PROPOSED);
        when(introductionRepo.findByIdForUpdate(introductionId)).thenReturn(Optional.of(intro));
        when(requestRepo.findById(requesterRequestId))
                .thenReturn(Optional.of(openRequestRow(requesterRequestId, requesterUserId)));
        when(requestRepo.findById(candidateRequestId))
                .thenReturn(Optional.of(openRequestRow(candidateRequestId, candidateUserId)));
        var bothInterested = introRowWithDecisions(introductionId,
                MatchmakingConstants.DECISION_INTERESTED,
                MatchmakingConstants.DECISION_INTERESTED,
                MatchmakingConstants.INTRO_MATCHED);
        when(introductionRepo.findById(introductionId))
                .thenReturn(Optional.of(bothInterested))
                .thenReturn(Optional.of(bothInterested));
        when(matchService.createMatchmakingMatch(introductionId))
                .thenReturn(new MatchResult(true, UUID.randomUUID()));

        service.recordDecision(introductionId, candidateUserId,
                MatchmakingConstants.DECISION_INTERESTED);

        verify(introductionRepo).setCandidateDecision(introductionId,
                MatchmakingConstants.DECISION_INTERESTED);
        verify(matchService).createMatchmakingMatch(introductionId);
        verify(requestRepo, never()).releaseOnHold(any(), any());
    }

    @Test
    void recordDecision_oneDeclines_declinedAndReleasesHold() {
        var intro = proposedIntroRow(introductionId);
        when(introductionRepo.findByIdForUpdate(introductionId)).thenReturn(Optional.of(intro));
        when(requestRepo.findById(requesterRequestId))
                .thenReturn(Optional.of(openRequestRow(requesterRequestId, requesterUserId)));
        when(requestRepo.findById(candidateRequestId))
                .thenReturn(Optional.of(openRequestRow(candidateRequestId, candidateUserId)));
        var notInterested = introRowWithDecisions(introductionId,
                MatchmakingConstants.DECISION_NOT_INTERESTED,
                MatchmakingConstants.DECISION_PENDING,
                MatchmakingConstants.INTRO_PROPOSED);
        when(introductionRepo.findById(introductionId))
                .thenReturn(Optional.of(notInterested))
                .thenReturn(Optional.of(notInterested));

        service.recordDecision(introductionId, requesterUserId,
                MatchmakingConstants.DECISION_NOT_INTERESTED);

        verify(introductionRepo).transitionStatus(introductionId,
                MatchmakingConstants.INTRO_PROPOSED, MatchmakingConstants.INTRO_DECLINED);
        // Quota not exhausted → both requests settle back to OPEN.
        verify(requestRepo).updateStatus(requesterRequestId,
                MatchmakingConstants.REQUEST_ON_HOLD, MatchmakingConstants.REQUEST_OPEN);
        verify(requestRepo).updateStatus(candidateRequestId,
                MatchmakingConstants.REQUEST_ON_HOLD, MatchmakingConstants.REQUEST_OPEN);
        verify(notificationDispatcher).dispatchMatchmakingIntroductionDeclinedNotification(
                eq(requesterUserId), eq(candidateUserId), eq(introductionId),
                eq(requesterRequestId), eq(candidateRequestId));
    }

    @Test
    void recordDecision_alreadyDecided_throws409() {
        var intro = introRowWithDecisions(introductionId,
                MatchmakingConstants.DECISION_INTERESTED,
                MatchmakingConstants.DECISION_PENDING, MatchmakingConstants.INTRO_PROPOSED);
        when(introductionRepo.findByIdForUpdate(introductionId)).thenReturn(Optional.of(intro));
        when(requestRepo.findById(requesterRequestId))
                .thenReturn(Optional.of(openRequestRow(requesterRequestId, requesterUserId)));
        when(requestRepo.findById(candidateRequestId))
                .thenReturn(Optional.of(openRequestRow(candidateRequestId, candidateUserId)));

        // Requester tries to flip their submitted decision.
        assertThatThrownBy(() -> service.recordDecision(introductionId, requesterUserId,
                MatchmakingConstants.DECISION_NOT_INTERESTED))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already submitted");
        verify(introductionRepo, never()).setRequesterDecision(any(), any());
    }

    // ── expireIntroductions ───────────────────────────────────────────────

    @Test
    void expireIntroductions_expiredRow_setsExpiredAndSettlesRequests() {
        var intro = proposedIntroRow(introductionId);
        when(introductionRepo.findExpiredProposed()).thenReturn(List.of(intro));
        when(introductionRepo.expire(introductionId)).thenReturn(true);
        when(requestRepo.findById(requesterRequestId))
                .thenReturn(Optional.of(requestRow(requesterRequestId, requesterUserId,
                        MatchmakingConstants.REQUEST_ON_HOLD)));
        when(requestRepo.findById(candidateRequestId))
                .thenReturn(Optional.of(requestRow(candidateRequestId, candidateUserId,
                        MatchmakingConstants.REQUEST_ON_HOLD)));

        var result = service.expireIntroductions();

        assertThat(result).containsExactly(introductionId);
        verify(requestRepo).updateStatus(requesterRequestId,
                MatchmakingConstants.REQUEST_ON_HOLD, MatchmakingConstants.REQUEST_OPEN);
        verify(requestRepo).updateStatus(candidateRequestId,
                MatchmakingConstants.REQUEST_ON_HOLD, MatchmakingConstants.REQUEST_OPEN);
    }

    @Test
    void expireIntroductions_noneExpired_returnsEmpty() {
        when(introductionRepo.findExpiredProposed()).thenReturn(List.of());

        assertThat(service.expireIntroductions()).isEmpty();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private RequestRow openRequestRow(UUID id, UUID uid) {
        return requestRow(id, uid, MatchmakingConstants.REQUEST_OPEN);
    }

    private RequestRow requestRow(UUID id, UUID uid, String status) {
        UUID pref = id.equals(requesterRequestId) ? requesterPrefId : candidatePrefId;
        return new RequestRow(id, uid, pref, status,
                UUID.randomUUID(), null, OffsetDateTime.now(), null, null,
                OffsetDateTime.now().plusDays(90), OffsetDateTime.now(), null);
    }

    private PreferenceRow prefRow(UUID id) {
        return new PreferenceRow(id, requesterUserId, 1, "FEMALE",
                null, null, null, null,
                new String[0], "any", "any",
                new String[0], new String[0], new String[0],
                new String[0], new String[0],
                new UUID[0], new UUID[0],
                null, null,
                null, null, null, null, null,
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    private IntroductionRow proposedIntroRow(UUID id) {
        return introRowWithDecisions(id,
                MatchmakingConstants.DECISION_PENDING,
                MatchmakingConstants.DECISION_PENDING,
                MatchmakingConstants.INTRO_PROPOSED);
    }

    private IntroductionRow introRowWithDecisions(UUID id, String requesterDec,
                                                  String candidateDec, String status) {
        return new IntroductionRow(id, requesterRequestId, candidateRequestId, adminUserId,
                requesterDec, candidateDec, status,
                80, 70, 75, "[]", "compatible",
                null, null,
                OffsetDateTime.now().plusHours(72),
                null,
                OffsetDateTime.now(), null, OffsetDateTime.now(), OffsetDateTime.now());
    }
}
