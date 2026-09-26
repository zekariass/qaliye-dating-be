package com.qaliye.backend.blinddate.service;

import com.qaliye.backend.blinddate.BlindDateConstants;
import com.qaliye.backend.blinddate.repository.BlindDateFinalDecisionRepository;
import com.qaliye.backend.blinddate.repository.BlindDateParticipantRepository;
import com.qaliye.backend.blinddate.repository.BlindDateParticipantRepository.ParticipantRow;
import com.qaliye.backend.blinddate.repository.BlindDateParticipantRepository.SelectionRow;
import com.qaliye.backend.blinddate.repository.BlindDateSessionRepository;
import com.qaliye.backend.blinddate.repository.BlindDateSessionRepository.RoundRow;
import com.qaliye.backend.blinddate.repository.BlindDateSessionRepository.SessionRow;
import com.qaliye.backend.notifications.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BlindDateSelectionServiceTest {

    @Mock BlindDateSessionRepository sessionRepo;
    @Mock BlindDateParticipantRepository participantRepo;
    @Mock BlindDateFinalDecisionRepository finalDecisionRepo;
    @Mock NotificationDispatcher notificationDispatcher;

    BlindDateSelectionService service;
    UUID creatorId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    UUID roundId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    UUID finalistUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new BlindDateSelectionService(sessionRepo, participantRepo,
                finalDecisionRepo, notificationDispatcher);
    }

    private SessionRow openSession() {
        return new SessionRow(sessionId, creatorId, BlindDateConstants.SESSION_OPEN, "en",
                null, null, null, OffsetDateTime.now());
    }

    private RoundRow openRound() {
        return new RoundRow(roundId, sessionId, 1, BlindDateConstants.ROUND_OPEN,
                OffsetDateTime.now(), OffsetDateTime.now(), null);
    }

    private ParticipantRow activeParticipant() {
        return new ParticipantRow(participantId, sessionId, finalistUserId,
                BlindDateConstants.PARTICIPANT_ACTIVE, roundId,
                OffsetDateTime.now(), null, null, null, null, null);
    }

    private void stubHappyPath() {
        when(sessionRepo.findSessionForUpdate(sessionId)).thenReturn(Optional.of(openSession()));
        when(sessionRepo.findOpenRound(sessionId)).thenReturn(Optional.of(openRound()));
        when(participantRepo.findParticipantForUpdate(participantId))
                .thenReturn(Optional.of(activeParticipant()));
    }

    @Test
    void select_advance_recordsSelectionOnly() {
        stubHappyPath();

        service.select(creatorId, sessionId, participantId, BlindDateConstants.DECISION_ADVANCE);

        verify(participantRepo).upsertSelection(roundId, participantId, creatorId,
                BlindDateConstants.DECISION_ADVANCE);
        verify(participantRepo, never()).eliminateParticipant(any());
        verify(sessionRepo, never()).transitionToReveal(any());
    }

    @Test
    void select_eliminate_marksParticipantAndNotifies() {
        stubHappyPath();

        service.select(creatorId, sessionId, participantId, BlindDateConstants.DECISION_ELIMINATE);

        verify(participantRepo).eliminateParticipant(participantId);
        verify(notificationDispatcher).dispatchBlindDateEliminatedNotification(finalistUserId, sessionId);
    }

    @Test
    void select_finalist_performsAtomicRevealTransition() {
        stubHappyPath();

        service.select(creatorId, sessionId, participantId,
                BlindDateConstants.DECISION_SELECT_FINALIST);

        var inOrder = inOrder(sessionRepo, participantRepo, finalDecisionRepo, notificationDispatcher);
        inOrder.verify(sessionRepo).closeRound(roundId);
        inOrder.verify(participantRepo).eliminateAllStillActiveExcept(sessionId, participantId);
        inOrder.verify(participantRepo).promoteToFinalist(participantId);
        inOrder.verify(participantRepo).markRevealed(participantId);
        inOrder.verify(sessionRepo).transitionToReveal(sessionId);
        inOrder.verify(finalDecisionRepo).insert(eq(sessionId), eq(participantId), any());
        inOrder.verify(notificationDispatcher).dispatchBlindDateRevealNotification(
                creatorId, finalistUserId, sessionId);
    }

    @Test
    void select_invalidDecision_throwsBadRequest() {
        assertThatThrownBy(() -> service.select(creatorId, sessionId, participantId, "BOGUS"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_decision");
    }

    @Test
    void select_notCreator_throwsForbidden() {
        when(sessionRepo.findSessionForUpdate(sessionId)).thenReturn(Optional.of(openSession()));

        assertThatThrownBy(() -> service.select(UUID.randomUUID(), sessionId, participantId,
                BlindDateConstants.DECISION_ADVANCE))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not_session_creator");
    }

    @Test
    void select_participantNotInRound_throwsConflict() {
        ParticipantRow wrongRound = new ParticipantRow(participantId, sessionId, finalistUserId,
                BlindDateConstants.PARTICIPANT_ACTIVE, UUID.randomUUID(),
                OffsetDateTime.now(), null, null, null, null, null);
        when(sessionRepo.findSessionForUpdate(sessionId)).thenReturn(Optional.of(openSession()));
        when(sessionRepo.findOpenRound(sessionId)).thenReturn(Optional.of(openRound()));
        when(participantRepo.findParticipantForUpdate(participantId))
                .thenReturn(Optional.of(wrongRound));

        assertThatThrownBy(() -> service.select(creatorId, sessionId, participantId,
                BlindDateConstants.DECISION_ADVANCE))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("participant_not_in_round");
    }

    @Test
    void closeRound_advancesMarkedAndEliminatesRest() {
        UUID advancedId = UUID.randomUUID();
        UUID undecidedId = UUID.randomUUID();
        ParticipantRow advanced = new ParticipantRow(advancedId, sessionId, UUID.randomUUID(),
                BlindDateConstants.PARTICIPANT_ACTIVE, roundId,
                OffsetDateTime.now(), null, null, null, null, null);
        ParticipantRow undecided = new ParticipantRow(undecidedId, sessionId, UUID.randomUUID(),
                BlindDateConstants.PARTICIPANT_ACTIVE, roundId,
                OffsetDateTime.now(), null, null, null, null, null);
        SelectionRow advanceSelection = new SelectionRow(UUID.randomUUID(), roundId, advancedId,
                creatorId, BlindDateConstants.DECISION_ADVANCE, OffsetDateTime.now());

        when(sessionRepo.findSessionForUpdate(sessionId)).thenReturn(Optional.of(openSession()));
        when(sessionRepo.findOpenRound(sessionId)).thenReturn(Optional.of(openRound()));
        when(participantRepo.findActiveParticipantsInRound(roundId))
                .thenReturn(List.of(advanced, undecided));
        when(participantRepo.findSelectionsForRound(roundId)).thenReturn(List.of(advanceSelection));

        service.closeRound(creatorId, sessionId);

        verify(participantRepo).advanceParticipant(advancedId, roundId);
        verify(participantRepo).eliminateParticipant(undecidedId);
        verify(sessionRepo).closeRound(roundId);
    }
}
