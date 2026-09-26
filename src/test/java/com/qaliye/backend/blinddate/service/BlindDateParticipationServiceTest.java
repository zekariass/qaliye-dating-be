package com.qaliye.backend.blinddate.service;

import com.qaliye.backend.blinddate.BlindDateConstants;
import com.qaliye.backend.blinddate.config.BlindDateProperties;
import com.qaliye.backend.blinddate.repository.BlindDateParticipantRepository;
import com.qaliye.backend.blinddate.repository.BlindDateParticipantRepository.ParticipantRow;
import com.qaliye.backend.blinddate.repository.BlindDateSessionRepository;
import com.qaliye.backend.blinddate.repository.BlindDateSessionRepository.RoundRow;
import com.qaliye.backend.blinddate.repository.BlindDateSessionRepository.SessionQuestionRow;
import com.qaliye.backend.blinddate.repository.BlindDateSessionRepository.SessionRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
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
class BlindDateParticipationServiceTest {

    @Mock BlindDateSessionRepository sessionRepo;
    @Mock BlindDateParticipantRepository participantRepo;
    @Mock BlindDateChargeService chargeService;

    BlindDateParticipationService service;
    BlindDateProperties properties;
    UUID userId = UUID.randomUUID();
    UUID creatorId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    UUID roundId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        properties = new BlindDateProperties();
        service = new BlindDateParticipationService(sessionRepo, participantRepo, chargeService, properties);
    }

    private SessionRow openSession() {
        return new SessionRow(sessionId, creatorId, BlindDateConstants.SESSION_OPEN, "en",
                null, null, null, OffsetDateTime.now());
    }

    private RoundRow round1() {
        return new RoundRow(roundId, sessionId, 1, BlindDateConstants.ROUND_OPEN,
                OffsetDateTime.now(), OffsetDateTime.now(), null);
    }

    private ParticipantRow activeParticipant() {
        return new ParticipantRow(participantId, sessionId, userId,
                BlindDateConstants.PARTICIPANT_ACTIVE, roundId,
                OffsetDateTime.now(), null, null, null, null, null);
    }

    @Test
    void join_idempotentReplay_returnsExistingWithoutCharging() {
        when(participantRepo.findByIdempotencyKey(idempotencyKey))
                .thenReturn(Optional.of(activeParticipant()));

        ParticipantRow result = service.join(userId, sessionId, idempotencyKey);

        assertThat(result.id()).isEqualTo(participantId);
        verify(chargeService, never()).charge(any(), any(), any());
        verify(participantRepo, never()).insertParticipant(any(), any(), any(), any());
    }

    @Test
    void join_success_chargesAndInserts() {
        when(participantRepo.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(sessionRepo.findSessionForUpdate(sessionId)).thenReturn(Optional.of(openSession()));
        when(participantRepo.hasParticipated(sessionId, userId)).thenReturn(false);
        when(sessionRepo.findOpenRound(sessionId)).thenReturn(Optional.of(round1()));
        when(participantRepo.insertParticipant(sessionId, userId, roundId, idempotencyKey))
                .thenReturn(participantId);
        when(participantRepo.findParticipant(participantId)).thenReturn(Optional.of(activeParticipant()));

        ParticipantRow result = service.join(userId, sessionId, idempotencyKey);

        assertThat(result.id()).isEqualTo(participantId);
        verify(chargeService).charge(userId, BlindDateConstants.ACTION_PARTICIPATE,
                "blind-date-participate:" + idempotencyKey);
    }

    @Test
    void join_creatorCannotJoinOwnSession() {
        when(participantRepo.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(sessionRepo.findSessionForUpdate(sessionId)).thenReturn(Optional.of(openSession()));

        assertThatThrownBy(() -> service.join(creatorId, sessionId, idempotencyKey))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("creator_cannot_join");
        verify(chargeService, never()).charge(any(), any(), any());
    }

    @Test
    void join_sessionNotOpen_throwsConflict() {
        SessionRow closed = new SessionRow(sessionId, creatorId, BlindDateConstants.SESSION_CLOSED,
                "en", null, OffsetDateTime.now(), null, OffsetDateTime.now());
        when(participantRepo.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(sessionRepo.findSessionForUpdate(sessionId)).thenReturn(Optional.of(closed));

        assertThatThrownBy(() -> service.join(userId, sessionId, idempotencyKey))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("session_not_open");
    }

    @Test
    void join_expiredSession_throwsConflict() {
        SessionRow expired = new SessionRow(sessionId, creatorId, BlindDateConstants.SESSION_OPEN,
                "en", OffsetDateTime.now().minusHours(1), null, null, OffsetDateTime.now());
        when(participantRepo.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(sessionRepo.findSessionForUpdate(sessionId)).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.join(userId, sessionId, idempotencyKey))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("session_expired");
    }

    @Test
    void getMySessions_delegatesToRepoWithClampedPaging() {
        var row = new BlindDateSessionRepository.MySessionRow(
                openSession(), participantId, BlindDateConstants.PARTICIPANT_ACTIVE,
                roundId, 3, 1, 0);
        when(sessionRepo.findMySessions(userId, 0, 50)).thenReturn(List.of(row));

        var result = service.getMySessions(userId, -1, 500);

        assertThat(result).containsExactly(row);
        verify(sessionRepo).findMySessions(userId, 0, 50);
    }

    @Test
    void getMyParticipations_delegatesToRepoWithClampedPaging() {
        var row = new BlindDateSessionRepository.MySessionRow(
                openSession(), participantId, BlindDateConstants.PARTICIPANT_ACTIVE,
                roundId, 3, 1, 0);
        when(sessionRepo.findMyParticipations(userId, 0, 20)).thenReturn(List.of(row));

        var result = service.getMyParticipations(userId, 0, 20);

        assertThat(result).containsExactly(row);
        verify(sessionRepo).findMyParticipations(userId, 0, 20);
    }

    @Test
    void join_sessionFull_throwsConflict() {
        properties.setMaxParticipants(2);
        when(participantRepo.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(sessionRepo.findSessionForUpdate(sessionId)).thenReturn(Optional.of(openSession()));
        when(participantRepo.hasParticipated(sessionId, userId)).thenReturn(false);
        when(participantRepo.countStillActiveInSession(sessionId)).thenReturn(2);

        assertThatThrownBy(() -> service.join(userId, sessionId, idempotencyKey))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("session_full");
        verify(chargeService, never()).charge(any(), any(), any());
        verify(participantRepo, never()).insertParticipant(any(), any(), any(), any());
    }

    @Test
    void join_alreadyJoined_throwsConflict() {
        when(participantRepo.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(sessionRepo.findSessionForUpdate(sessionId)).thenReturn(Optional.of(openSession()));
        when(participantRepo.hasParticipated(sessionId, userId)).thenReturn(true);

        assertThatThrownBy(() -> service.join(userId, sessionId, idempotencyKey))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already_joined");
    }

    @Test
    void join_round2Open_throwsJoinWindowClosed() {
        RoundRow round2 = new RoundRow(UUID.randomUUID(), sessionId, 2,
                BlindDateConstants.ROUND_OPEN, OffsetDateTime.now(), OffsetDateTime.now(), null);
        when(participantRepo.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(sessionRepo.findSessionForUpdate(sessionId)).thenReturn(Optional.of(openSession()));
        when(participantRepo.hasParticipated(sessionId, userId)).thenReturn(false);
        when(sessionRepo.findOpenRound(sessionId)).thenReturn(Optional.of(round2));

        assertThatThrownBy(() -> service.join(userId, sessionId, idempotencyKey))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("join_window_closed");
    }

    @Test
    void join_duplicateKey_throwsAlreadyJoined() {
        when(participantRepo.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(sessionRepo.findSessionForUpdate(sessionId)).thenReturn(Optional.of(openSession()));
        when(participantRepo.hasParticipated(sessionId, userId)).thenReturn(false);
        when(sessionRepo.findOpenRound(sessionId)).thenReturn(Optional.of(round1()));
        when(participantRepo.insertParticipant(sessionId, userId, roundId, idempotencyKey))
                .thenThrow(new DuplicateKeyException("uq_bd_session_participant"));

        assertThatThrownBy(() -> service.join(userId, sessionId, idempotencyKey))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already_joined");
    }

    @Test
    void submitAnswers_success_upsertsEachAnswer() {
        UUID q1 = UUID.randomUUID();
        UUID q2 = UUID.randomUUID();
        var sq1 = new SessionQuestionRow(q1, sessionId, roundId, UUID.randomUUID(), null,
                "Q1?", "A1", "en", 1);
        var sq2 = new SessionQuestionRow(q2, sessionId, roundId, UUID.randomUUID(), null,
                "Q2?", "A2", "en", 2);

        when(participantRepo.findParticipantForUpdate(participantId))
                .thenReturn(Optional.of(activeParticipant()));
        when(sessionRepo.findRound(roundId)).thenReturn(Optional.of(round1()));
        when(sessionRepo.findQuestionsForRound(roundId)).thenReturn(List.of(sq1, sq2));
        when(participantRepo.isAnswerEditable(eq(participantId), any())).thenReturn(true);

        service.submitAnswers(userId, participantId, Map.of(q1, "answer one", q2, "answer two"));

        verify(participantRepo).upsertAnswer(participantId, q1, "answer one");
        verify(participantRepo).upsertAnswer(participantId, q2, "answer two");
    }

    @Test
    void submitAnswers_questionNotInRound_throwsBadRequest() {
        UUID foreignQuestion = UUID.randomUUID();
        when(participantRepo.findParticipantForUpdate(participantId))
                .thenReturn(Optional.of(activeParticipant()));
        when(sessionRepo.findRound(roundId)).thenReturn(Optional.of(round1()));
        when(sessionRepo.findQuestionsForRound(roundId)).thenReturn(List.of());

        assertThatThrownBy(() -> service.submitAnswers(userId, participantId,
                Map.of(foreignQuestion, "answer")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("question_not_in_round");
    }

    @Test
    void submitAnswers_lockedAnswer_throwsConflict() {
        UUID q1 = UUID.randomUUID();
        var sq1 = new SessionQuestionRow(q1, sessionId, roundId, UUID.randomUUID(), null,
                "Q1?", "A1", "en", 1);
        when(participantRepo.findParticipantForUpdate(participantId))
                .thenReturn(Optional.of(activeParticipant()));
        when(sessionRepo.findRound(roundId)).thenReturn(Optional.of(round1()));
        when(sessionRepo.findQuestionsForRound(roundId)).thenReturn(List.of(sq1));
        when(participantRepo.isAnswerEditable(participantId, q1)).thenReturn(false);

        assertThatThrownBy(() -> service.submitAnswers(userId, participantId,
                Map.of(q1, "new answer")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("answer_locked");
    }

    @Test
    void submitAnswers_notParticipant_throwsForbidden() {
        UUID otherUser = UUID.randomUUID();
        when(participantRepo.findParticipantForUpdate(participantId))
                .thenReturn(Optional.of(activeParticipant()));

        assertThatThrownBy(() -> service.submitAnswers(otherUser, participantId, Map.of()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not_participant");
    }

    @Test
    void submitAnswers_roundClosed_throwsConflict() {
        RoundRow closedRound = new RoundRow(roundId, sessionId, 1,
                BlindDateConstants.ROUND_CLOSED, OffsetDateTime.now(),
                OffsetDateTime.now(), OffsetDateTime.now());
        when(participantRepo.findParticipantForUpdate(participantId))
                .thenReturn(Optional.of(activeParticipant()));
        when(sessionRepo.findRound(roundId)).thenReturn(Optional.of(closedRound));

        assertThatThrownBy(() -> service.submitAnswers(userId, participantId, Map.of()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("round_closed");
    }

    @Test
    void withdraw_success() {
        when(participantRepo.findParticipantForUpdate(participantId))
                .thenReturn(Optional.of(activeParticipant()));
        when(participantRepo.withdrawParticipant(participantId)).thenReturn(true);

        service.withdraw(userId, participantId);

        verify(participantRepo).withdrawParticipant(participantId);
    }

    @Test
    void withdraw_notParticipant_throwsForbidden() {
        when(participantRepo.findParticipantForUpdate(participantId))
                .thenReturn(Optional.of(activeParticipant()));

        assertThatThrownBy(() -> service.withdraw(UUID.randomUUID(), participantId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not_participant");
    }

    @Test
    void withdraw_eliminatedParticipant_throwsConflict() {
        ParticipantRow eliminated = new ParticipantRow(participantId, sessionId, userId,
                BlindDateConstants.PARTICIPANT_ELIMINATED, roundId,
                OffsetDateTime.now(), OffsetDateTime.now(), null, null, null, null);
        when(participantRepo.findParticipantForUpdate(participantId))
                .thenReturn(Optional.of(eliminated));
        when(participantRepo.withdrawParticipant(participantId)).thenReturn(false);

        assertThatThrownBy(() -> service.withdraw(userId, participantId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("cannot_withdraw");
    }
}
