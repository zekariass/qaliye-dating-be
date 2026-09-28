package com.qaliye.backend.blinddate.service;

import com.qaliye.backend.blinddate.BlindDateConstants;
import com.qaliye.backend.blinddate.repository.BlindDateFinalDecisionRepository;
import com.qaliye.backend.blinddate.repository.BlindDateFinalDecisionRepository.FinalDecisionRow;
import com.qaliye.backend.blinddate.repository.BlindDateParticipantRepository;
import com.qaliye.backend.blinddate.repository.BlindDateParticipantRepository.ParticipantRow;
import com.qaliye.backend.blinddate.repository.BlindDateSessionRepository;
import com.qaliye.backend.blinddate.repository.BlindDateSessionRepository.SessionRow;
import com.qaliye.backend.notifications.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BlindDateFinalDecisionServiceTest {

    @Mock BlindDateSessionRepository sessionRepo;
    @Mock BlindDateParticipantRepository participantRepo;
    @Mock BlindDateFinalDecisionRepository finalDecisionRepo;
    @Mock BlindDateMatchService matchService;
    @Mock NotificationDispatcher notificationDispatcher;

    BlindDateFinalDecisionService service;
    UUID creatorId = UUID.randomUUID();
    UUID finalistUserId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    UUID finalistParticipantId = UUID.randomUUID();
    UUID matchId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new BlindDateFinalDecisionService(sessionRepo, participantRepo,
                finalDecisionRepo, matchService, notificationDispatcher);
    }

    private SessionRow revealSession() {
        return new SessionRow(sessionId, creatorId, BlindDateConstants.SESSION_REVEAL, "en",
                null, null, null, OffsetDateTime.now());
    }

    private ParticipantRow finalist() {
        return new ParticipantRow(finalistParticipantId, sessionId, finalistUserId,
                BlindDateConstants.PARTICIPANT_REVEALED, null,
                OffsetDateTime.now(), null, null, OffsetDateTime.now(), null, OffsetDateTime.now());
    }

    private FinalDecisionRow pendingDecision() {
        return new FinalDecisionRow(UUID.randomUUID(), sessionId, finalistParticipantId,
                OffsetDateTime.now(), OffsetDateTime.now().plusHours(48),
                BlindDateConstants.FINAL_PENDING, BlindDateConstants.FINAL_PENDING,
                null, null, null, null);
    }

    private void stubReveal(FinalDecisionRow fd) {
        when(sessionRepo.findSessionForUpdate(sessionId)).thenReturn(Optional.of(revealSession()));
        when(finalDecisionRepo.findBySessionForUpdate(sessionId)).thenReturn(Optional.of(fd));
        lenient().when(participantRepo.findParticipantForUpdate(finalistParticipantId))
                .thenReturn(Optional.of(finalist()));
    }

    @Test
    void decide_firstSide_recordsDecisionNoOutcome() {
        FinalDecisionRow fd = pendingDecision();
        stubReveal(fd);
        // After setting creator decision, participant still pending → no resolution.
        FinalDecisionRow after = new FinalDecisionRow(fd.id(), sessionId, finalistParticipantId,
                fd.revealedAt(), fd.decisionDeadlineAt(),
                BlindDateConstants.FINAL_INTERESTED, BlindDateConstants.FINAL_PENDING,
                OffsetDateTime.now(), null, null, null);
        when(finalDecisionRepo.findBySession(sessionId)).thenReturn(Optional.of(after));

        service.decide(creatorId, sessionId, BlindDateConstants.FINAL_INTERESTED);

        verify(finalDecisionRepo).setCreatorDecision(sessionId, BlindDateConstants.FINAL_INTERESTED);
        verify(finalDecisionRepo, never()).setOutcome(any(), any(), any());
        verify(sessionRepo, never()).completeSession(any());
    }

    @Test
    void decide_mutualInterested_createsMatch() {
        FinalDecisionRow fd = pendingDecision();
        stubReveal(fd);
        // Creator already interested; participant now decides interested.
        FinalDecisionRow after = new FinalDecisionRow(fd.id(), sessionId, finalistParticipantId,
                fd.revealedAt(), fd.decisionDeadlineAt(),
                BlindDateConstants.FINAL_INTERESTED, BlindDateConstants.FINAL_INTERESTED,
                OffsetDateTime.now(), OffsetDateTime.now(), null, null);
        when(finalDecisionRepo.findBySession(sessionId)).thenReturn(Optional.of(after));
        when(matchService.createBlindDateMatch(creatorId, finalistUserId, sessionId))
                .thenReturn(new BlindDateMatchService.MatchResult(true, matchId));

        service.decide(finalistUserId, sessionId, BlindDateConstants.FINAL_INTERESTED);

        verify(finalDecisionRepo).setParticipantDecision(sessionId, BlindDateConstants.FINAL_INTERESTED);
        verify(finalDecisionRepo).setOutcome(sessionId, BlindDateConstants.OUTCOME_MATCHED, matchId);
        verify(sessionRepo).completeSession(sessionId);
        verify(notificationDispatcher).dispatchBlindDateOutcomeNotification(
                creatorId, finalistUserId, sessionId, true);
    }

    @Test
    void decide_oneNotInterested_resolvesNoMatch() {
        FinalDecisionRow fd = pendingDecision();
        stubReveal(fd);
        FinalDecisionRow after = new FinalDecisionRow(fd.id(), sessionId, finalistParticipantId,
                fd.revealedAt(), fd.decisionDeadlineAt(),
                BlindDateConstants.FINAL_INTERESTED, BlindDateConstants.FINAL_NOT_INTERESTED,
                OffsetDateTime.now(), OffsetDateTime.now(), null, null);
        when(finalDecisionRepo.findBySession(sessionId)).thenReturn(Optional.of(after));

        service.decide(finalistUserId, sessionId, BlindDateConstants.FINAL_NOT_INTERESTED);

        verify(finalDecisionRepo).setOutcome(sessionId, BlindDateConstants.OUTCOME_NO_MATCH, null);
        verify(sessionRepo).completeSession(sessionId);
        verify(matchService, never()).createBlindDateMatch(any(), any(), any());
        verify(notificationDispatcher).dispatchBlindDateOutcomeNotification(
                creatorId, finalistUserId, sessionId, false);
    }

    @Test
    void decide_mutualInterestedButAlreadyMatched_resolvesAlreadyMatched() {
        FinalDecisionRow fd = pendingDecision();
        stubReveal(fd);
        FinalDecisionRow after = new FinalDecisionRow(fd.id(), sessionId, finalistParticipantId,
                fd.revealedAt(), fd.decisionDeadlineAt(),
                BlindDateConstants.FINAL_INTERESTED, BlindDateConstants.FINAL_INTERESTED,
                OffsetDateTime.now(), OffsetDateTime.now(), null, null);
        when(finalDecisionRepo.findBySession(sessionId)).thenReturn(Optional.of(after));
        when(matchService.createBlindDateMatch(creatorId, finalistUserId, sessionId))
                .thenReturn(new BlindDateMatchService.MatchResult(false, matchId));

        service.decide(finalistUserId, sessionId, BlindDateConstants.FINAL_INTERESTED);

        verify(finalDecisionRepo).setOutcome(sessionId,
                BlindDateConstants.OUTCOME_ALREADY_MATCHED, matchId);
    }

    @Test
    void decide_mutualInterestedButBlocked_resolvesNoMatch() {
        FinalDecisionRow fd = pendingDecision();
        stubReveal(fd);
        FinalDecisionRow after = new FinalDecisionRow(fd.id(), sessionId, finalistParticipantId,
                fd.revealedAt(), fd.decisionDeadlineAt(),
                BlindDateConstants.FINAL_INTERESTED, BlindDateConstants.FINAL_INTERESTED,
                OffsetDateTime.now(), OffsetDateTime.now(), null, null);
        when(finalDecisionRepo.findBySession(sessionId)).thenReturn(Optional.of(after));
        when(matchService.createBlindDateMatch(creatorId, finalistUserId, sessionId))
                .thenReturn(new BlindDateMatchService.MatchResult(false, null));

        service.decide(finalistUserId, sessionId, BlindDateConstants.FINAL_INTERESTED);

        verify(finalDecisionRepo).setOutcome(sessionId, BlindDateConstants.OUTCOME_NO_MATCH, null);
    }

    @Test
    void decide_notAParty_throwsForbidden() {
        stubReveal(pendingDecision());

        assertThatThrownBy(() -> service.decide(UUID.randomUUID(), sessionId,
                BlindDateConstants.FINAL_INTERESTED))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not_a_decision_party");
    }

    @Test
    void decide_sessionNotInReveal_throwsConflict() {
        SessionRow open = new SessionRow(sessionId, creatorId, BlindDateConstants.SESSION_OPEN,
                "en", null, null, null, OffsetDateTime.now());
        when(sessionRepo.findSessionForUpdate(sessionId)).thenReturn(Optional.of(open));

        assertThatThrownBy(() -> service.decide(creatorId, sessionId,
                BlindDateConstants.FINAL_INTERESTED))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("session_not_in_reveal");
    }

    @Test
    void decide_alreadyResolved_throwsConflict() {
        FinalDecisionRow resolved = new FinalDecisionRow(UUID.randomUUID(), sessionId,
                finalistParticipantId, OffsetDateTime.now(), OffsetDateTime.now().plusHours(48),
                BlindDateConstants.FINAL_INTERESTED, BlindDateConstants.FINAL_INTERESTED,
                OffsetDateTime.now(), OffsetDateTime.now(),
                BlindDateConstants.OUTCOME_MATCHED, matchId);
        stubReveal(resolved);

        assertThatThrownBy(() -> service.decide(creatorId, sessionId,
                BlindDateConstants.FINAL_INTERESTED))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("decision_already_resolved");
    }

    @Test
    void decide_duplicateSubmission_throwsConflict() {
        FinalDecisionRow fd = new FinalDecisionRow(UUID.randomUUID(), sessionId,
                finalistParticipantId, OffsetDateTime.now(), OffsetDateTime.now().plusHours(48),
                BlindDateConstants.FINAL_INTERESTED, BlindDateConstants.FINAL_PENDING,
                OffsetDateTime.now(), null, null, null);
        stubReveal(fd);

        assertThatThrownBy(() -> service.decide(creatorId, sessionId,
                BlindDateConstants.FINAL_NOT_INTERESTED))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("decision_already_submitted");
    }

    @Test
    void expireFinalDecision_resolvesAsExpired() {
        FinalDecisionRow fd = pendingDecision();
        when(sessionRepo.findSessionForUpdate(sessionId)).thenReturn(Optional.of(revealSession()));
        when(finalDecisionRepo.findBySessionForUpdate(sessionId)).thenReturn(Optional.of(fd));
        when(participantRepo.findParticipant(finalistParticipantId))
                .thenReturn(Optional.of(finalist()));

        service.expireFinalDecision(fd);

        verify(finalDecisionRepo).resolvePendingAsNotInterested(sessionId);
        verify(finalDecisionRepo).setOutcome(sessionId, BlindDateConstants.OUTCOME_EXPIRED, null);
        verify(sessionRepo).completeSession(sessionId);
    }
}
