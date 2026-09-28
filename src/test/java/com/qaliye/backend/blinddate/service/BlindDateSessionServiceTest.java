package com.qaliye.backend.blinddate.service;

import com.qaliye.backend.blinddate.BlindDateConstants;
import com.qaliye.backend.blinddate.config.BlindDateProperties;
import com.qaliye.backend.blinddate.repository.BlindDateCatalogRepository;
import com.qaliye.backend.blinddate.repository.BlindDateFinalDecisionRepository;
import com.qaliye.backend.blinddate.repository.BlindDateFinalDecisionRepository.FinalDecisionRow;
import com.qaliye.backend.blinddate.repository.BlindDateParticipantRepository;
import com.qaliye.backend.blinddate.repository.BlindDateQuestionSetRepository;
import com.qaliye.backend.blinddate.repository.BlindDateQuestionSetRepository.ConfigurationRow;
import com.qaliye.backend.blinddate.repository.BlindDateQuestionSetRepository.SetQuestionRow;
import com.qaliye.backend.blinddate.repository.BlindDateSessionRepository;
import com.qaliye.backend.blinddate.repository.BlindDateSessionRepository.CreatorInfoRow;
import com.qaliye.backend.blinddate.repository.BlindDateSessionRepository.RoundRow;
import com.qaliye.backend.blinddate.repository.BlindDateSessionRepository.SessionRow;
import com.qaliye.backend.discovery.service.StorageSigningService;
import com.qaliye.backend.notifications.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BlindDateSessionServiceTest {

    @Mock BlindDateSessionRepository sessionRepo;
    @Mock BlindDateParticipantRepository participantRepo;
    @Mock BlindDateQuestionSetRepository questionSetRepo;
    @Mock BlindDateCatalogRepository catalogRepo;
    @Mock BlindDateFinalDecisionRepository finalDecisionRepo;
    @Mock BlindDateChargeService chargeService;
    @Mock StorageSigningService signingService;
    @Mock NotificationDispatcher notificationDispatcher;

    BlindDateSessionService service;
    BlindDateProperties properties;
    UUID creatorId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    UUID roundId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        properties = new BlindDateProperties();
        service = new BlindDateSessionService(sessionRepo, participantRepo, questionSetRepo,
                catalogRepo, finalDecisionRepo, chargeService, properties, signingService,
                notificationDispatcher);
    }

    private SessionRow openSession() {
        return new SessionRow(sessionId, creatorId, BlindDateConstants.SESSION_OPEN, "en",
                null, null, null, OffsetDateTime.now());
    }

    @Test
    void createSession_idempotentReplay_returnsExistingWithoutCharging() {
        when(sessionRepo.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(openSession()));
        when(sessionRepo.findRoundsForSession(sessionId)).thenReturn(List.of());

        var view = service.createSession(creatorId, idempotencyKey, List.of(UUID.randomUUID()),
                null, null, null);

        assertThat(view.session().id()).isEqualTo(sessionId);
        verify(chargeService, never()).charge(any(), any(), any());
        verify(sessionRepo, never()).insertSession(any(), any(), any(), any());
    }

    @Test
    void createSession_noQuestions_throwsBadRequest() {
        when(sessionRepo.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(questionSetRepo.findConfiguration(creatorId))
                .thenReturn(Optional.of(new ConfigurationRow(UUID.randomUUID(), creatorId, true, "en")));
        when(catalogRepo.isSupportedLanguage("en")).thenReturn(true);

        assertThatThrownBy(() -> service.createSession(creatorId, idempotencyKey,
                null, null, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_question_count");
        verify(chargeService, never()).charge(any(), any(), any());
    }

    @Test
    void createSession_success_chargesAndSnapshots() {
        UUID questionId = UUID.randomUUID();
        UUID setId = UUID.randomUUID();
        SetQuestionRow setQuestion = new SetQuestionRow(UUID.randomUUID(), questionId,
                "What do you value?", "Honesty", 1, true, UUID.randomUUID(), "VALUES_BELIEFS");

        when(sessionRepo.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(questionSetRepo.findConfiguration(creatorId))
                .thenReturn(Optional.of(new ConfigurationRow(UUID.randomUUID(), creatorId, true, "en")));
        when(catalogRepo.isSupportedLanguage("en")).thenReturn(true);
        when(sessionRepo.insertSession(eq(creatorId), eq("en"), isNull(), eq(idempotencyKey)))
                .thenReturn(sessionId);
        when(sessionRepo.insertRound(sessionId, 1)).thenReturn(roundId);
        when(questionSetRepo.ensureQuestionSet(creatorId)).thenReturn(setId);
        when(questionSetRepo.findSetQuestions(setId, "en", false)).thenReturn(List.of(setQuestion));
        when(sessionRepo.insertSessionQuestion(eq(sessionId), eq(roundId), eq(questionId), isNull(),
                eq("What do you value?"), eq("Honesty"), eq("en"), eq(1)))
                .thenReturn(UUID.randomUUID());
        when(sessionRepo.findSession(sessionId)).thenReturn(Optional.of(openSession()));
        when(sessionRepo.findRoundsForSession(sessionId)).thenReturn(List.of());

        var view = service.createSession(creatorId, idempotencyKey, List.of(questionId),
                null, null, null);

        assertThat(view.session().id()).isEqualTo(sessionId);
        verify(chargeService).charge(creatorId, BlindDateConstants.ACTION_SESSION_CREATE,
                "blind-date-session:" + idempotencyKey);
        verify(sessionRepo).insertSessionQuestion(eq(sessionId), eq(roundId), eq(questionId),
                isNull(), eq("What do you value?"), eq("Honesty"), eq("en"), eq(1));
    }

    @Test
    void createSession_unansweredQuestion_throwsBadRequest() {
        UUID questionId = UUID.randomUUID();
        UUID setId = UUID.randomUUID();
        SetQuestionRow setQuestion = new SetQuestionRow(UUID.randomUUID(), questionId,
                "What do you value?", null, 1, true, UUID.randomUUID(), "VALUES_BELIEFS");

        when(sessionRepo.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(questionSetRepo.findConfiguration(creatorId))
                .thenReturn(Optional.of(new ConfigurationRow(UUID.randomUUID(), creatorId, true, "en")));
        when(catalogRepo.isSupportedLanguage("en")).thenReturn(true);
        when(sessionRepo.insertSession(eq(creatorId), eq("en"), isNull(), eq(idempotencyKey)))
                .thenReturn(sessionId);
        when(sessionRepo.insertRound(sessionId, 1)).thenReturn(roundId);
        when(questionSetRepo.ensureQuestionSet(creatorId)).thenReturn(setId);
        when(questionSetRepo.findSetQuestions(setId, "en", false)).thenReturn(List.of(setQuestion));

        assertThatThrownBy(() -> service.createSession(creatorId, idempotencyKey,
                List.of(questionId), null, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("question_unanswered");
    }

    @Test
    void createSession_duplicateActiveSession_throwsConflict() {
        UUID questionId = UUID.randomUUID();
        when(sessionRepo.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(questionSetRepo.findConfiguration(creatorId))
                .thenReturn(Optional.of(new ConfigurationRow(UUID.randomUUID(), creatorId, true, "en")));
        when(catalogRepo.isSupportedLanguage("en")).thenReturn(true);
        when(sessionRepo.insertSession(eq(creatorId), eq("en"), isNull(), eq(idempotencyKey)))
                .thenThrow(new DuplicateKeyException("uq_blind_date_one_active_session_per_creator"));

        assertThatThrownBy(() -> service.createSession(creatorId, idempotencyKey,
                List.of(questionId), null, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("active_session_exists");
    }

    @Test
    void createNextRound_notCreator_throwsForbidden() {
        UUID otherUser = UUID.randomUUID();
        when(sessionRepo.findSessionForUpdate(sessionId)).thenReturn(Optional.of(openSession()));

        assertThatThrownBy(() -> service.createNextRound(otherUser, sessionId,
                List.of(UUID.randomUUID()), null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not_session_creator");
    }

    @Test
    void createNextRound_openRoundExists_throwsConflict() {
        when(sessionRepo.findSessionForUpdate(sessionId)).thenReturn(Optional.of(openSession()));
        when(sessionRepo.findOpenRound(sessionId))
                .thenReturn(Optional.of(new RoundRow(roundId, sessionId, 1,
                        BlindDateConstants.ROUND_OPEN, OffsetDateTime.now(), OffsetDateTime.now(), null)));

        assertThatThrownBy(() -> service.createNextRound(creatorId, sessionId,
                List.of(UUID.randomUUID()), null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("round_still_open");
    }

    @Test
    void createNextRound_maxRoundsReached_throwsConflict() {
        properties.setMaxRounds(3);
        when(sessionRepo.findSessionForUpdate(sessionId)).thenReturn(Optional.of(openSession()));
        when(sessionRepo.findOpenRound(sessionId)).thenReturn(Optional.empty());
        when(sessionRepo.findLatestRoundNumber(sessionId)).thenReturn(3);

        assertThatThrownBy(() -> service.createNextRound(creatorId, sessionId,
                List.of(UUID.randomUUID()), null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("max_rounds_reached");
        verify(sessionRepo, never()).insertRound(any(), anyInt());
    }

    @Test
    void getSessionParticipants_notCreator_throwsForbidden() {
        when(sessionRepo.findSession(sessionId)).thenReturn(Optional.of(openSession()));

        assertThatThrownBy(() -> service.getSessionParticipants(UUID.randomUUID(), sessionId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not_session_creator");
    }

    @Test
    void getSessionParticipants_creator_returnsParticipantsWithAnswers() {
        UUID participantId = UUID.randomUUID();
        var participant = new BlindDateParticipantRepository.ParticipantRow(
                participantId, sessionId, UUID.randomUUID(), BlindDateConstants.PARTICIPANT_ACTIVE,
                roundId, OffsetDateTime.now(), null, null, null, null, null);
        var answer = new BlindDateParticipantRepository.ParticipantAnswerView(
                UUID.randomUUID(), "Q?", "A!", OffsetDateTime.now());

        when(sessionRepo.findSession(sessionId)).thenReturn(Optional.of(openSession()));
        when(sessionRepo.findOpenRound(sessionId))
                .thenReturn(Optional.of(new RoundRow(roundId, sessionId, 1,
                        BlindDateConstants.ROUND_OPEN, OffsetDateTime.now(), OffsetDateTime.now(), null)));
        when(participantRepo.findParticipantsForSession(sessionId)).thenReturn(List.of(participant));
        when(participantRepo.findAnswersWithQuestions(participantId, roundId)).thenReturn(List.of(answer));

        var views = service.getSessionParticipants(creatorId, sessionId);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).participant().id()).isEqualTo(participantId);
        assertThat(views.get(0).answers()).containsExactly(answer);
    }

    @Test
    void getSessionParticipants_noOpenRound_returnsEmptyAnswers() {
        UUID participantId = UUID.randomUUID();
        var participant = new BlindDateParticipantRepository.ParticipantRow(
                participantId, sessionId, UUID.randomUUID(), BlindDateConstants.PARTICIPANT_ACTIVE,
                null, OffsetDateTime.now(), null, null, null, null, null);

        when(sessionRepo.findSession(sessionId)).thenReturn(Optional.of(openSession()));
        when(sessionRepo.findOpenRound(sessionId)).thenReturn(Optional.empty());
        when(participantRepo.findParticipantsForSession(sessionId)).thenReturn(List.of(participant));

        var views = service.getSessionParticipants(creatorId, sessionId);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).answers()).isEmpty();
        verify(participantRepo, never()).findAnswersWithQuestions(any(), any());
    }

    // ── getSessionResults ─────────────────────────────────────────────────

    private SessionRow sessionWithStatus(String status) {
        return new SessionRow(sessionId, creatorId, status, "en",
                null, null, null, OffsetDateTime.now());
    }

    @Test
    void getSessionResults_notCreator_throwsForbidden() {
        when(sessionRepo.findSession(sessionId)).thenReturn(Optional.of(openSession()));

        assertThatThrownBy(() -> service.getSessionResults(UUID.randomUUID(), sessionId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not_session_creator");
    }

    @Test
    void getSessionResults_sessionNotFound_throwsNotFound() {
        when(sessionRepo.findSession(sessionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSessionResults(creatorId, sessionId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("session_not_found");
    }

    @Test
    void getSessionResults_completedSession_returnsWinnerWithAnswersPerRound() {
        UUID finalistParticipantId = UUID.randomUUID();
        UUID finalistUserId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        UUID round1Id = UUID.randomUUID();
        UUID round2Id = UUID.randomUUID();

        var decision = new FinalDecisionRow(UUID.randomUUID(), sessionId, finalistParticipantId,
                OffsetDateTime.now(), OffsetDateTime.now().plusHours(24),
                "INTERESTED", "INTERESTED", OffsetDateTime.now(), OffsetDateTime.now(),
                "MATCHED", matchId);
        var finalist = new BlindDateParticipantRepository.ParticipantRow(
                finalistParticipantId, sessionId, finalistUserId, BlindDateConstants.PARTICIPANT_REVEALED,
                round2Id, OffsetDateTime.now(), null, null, OffsetDateTime.now(), null,
                OffsetDateTime.now());
        var rounds = List.of(
                new RoundRow(round1Id, sessionId, 1, BlindDateConstants.ROUND_CLOSED,
                        OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now()),
                new RoundRow(round2Id, sessionId, 2, BlindDateConstants.ROUND_CLOSED,
                        OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now()));
        var creatorInfo = new CreatorInfoRow(finalistUserId, "Sara", "FEMALE", "ORTHODOX",
                "MARRIAGE", 27, "Addis Ababa", "Ethiopia",
                UUID.randomUUID(), "bucket", "path/photo.jpg");
        var answer1 = new BlindDateParticipantRepository.ParticipantAnswerView(
                UUID.randomUUID(), "Morning person?", "No", OffsetDateTime.now());
        var answer2 = new BlindDateParticipantRepository.ParticipantAnswerView(
                UUID.randomUUID(), "Coffee or tea?", "Coffee", OffsetDateTime.now());

        when(sessionRepo.findSession(sessionId))
                .thenReturn(Optional.of(sessionWithStatus(BlindDateConstants.SESSION_COMPLETED)));
        when(finalDecisionRepo.findBySession(sessionId)).thenReturn(Optional.of(decision));
        when(sessionRepo.findRoundsForSession(sessionId)).thenReturn(rounds);
        when(participantRepo.findParticipantsForSession(sessionId))
                .thenReturn(List.of(finalist,
                        new BlindDateParticipantRepository.ParticipantRow(UUID.randomUUID(), sessionId,
                                UUID.randomUUID(), BlindDateConstants.PARTICIPANT_ELIMINATED,
                                null, OffsetDateTime.now(), OffsetDateTime.now(), null, null, null, null)));
        when(participantRepo.findParticipant(finalistParticipantId)).thenReturn(Optional.of(finalist));
        when(sessionRepo.findCreatorInfo(List.of(finalistUserId))).thenReturn(List.of(creatorInfo));
        when(participantRepo.findAnswersWithQuestions(finalistParticipantId, round1Id))
                .thenReturn(List.of(answer1));
        when(participantRepo.findAnswersWithQuestions(finalistParticipantId, round2Id))
                .thenReturn(List.of(answer2));
        when(signingService.signPhoto(eq(creatorInfo.photoId()), eq(0), eq(true),
                eq("bucket"), eq("path/photo.jpg")))
                .thenReturn(new com.qaliye.backend.discovery.dto.DiscoveryPhotoDto(
                        creatorInfo.photoId(), 0, true, "https://signed", java.time.Instant.now()));

        var view = service.getSessionResults(creatorId, sessionId);

        assertThat(view.session().status()).isEqualTo(BlindDateConstants.SESSION_COMPLETED);
        assertThat(view.decision().outcome()).isEqualTo("MATCHED");
        assertThat(view.decision().matchId()).isEqualTo(matchId);
        assertThat(view.participantCount()).isEqualTo(2);
        assertThat(view.roundCount()).isEqualTo(2);
        assertThat(view.finalist().id()).isEqualTo(finalistParticipantId);
        assertThat(view.finalist().userId()).isEqualTo(finalistUserId);
        assertThat(view.profile().displayName()).isEqualTo("Sara");
        assertThat(view.profile().primaryPhoto().signedUrl()).isEqualTo("https://signed");
        assertThat(view.winnerRounds()).hasSize(2);
        assertThat(view.winnerRounds().get(0).round().roundNumber()).isEqualTo(1);
        assertThat(view.winnerRounds().get(0).answers()).containsExactly(answer1);
        assertThat(view.winnerRounds().get(1).round().roundNumber()).isEqualTo(2);
        assertThat(view.winnerRounds().get(1).answers()).containsExactly(answer2);
    }

    @Test
    void getSessionResults_noFinalDecision_returnsNullWinner() {
        when(sessionRepo.findSession(sessionId))
                .thenReturn(Optional.of(sessionWithStatus(BlindDateConstants.SESSION_CLOSED)));
        when(finalDecisionRepo.findBySession(sessionId)).thenReturn(Optional.empty());
        when(sessionRepo.findRoundsForSession(sessionId)).thenReturn(List.of(
                new RoundRow(roundId, sessionId, 1, BlindDateConstants.ROUND_CLOSED,
                        OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now())));
        when(participantRepo.findParticipantsForSession(sessionId)).thenReturn(List.of());

        var view = service.getSessionResults(creatorId, sessionId);

        assertThat(view.decision()).isNull();
        assertThat(view.finalist()).isNull();
        assertThat(view.profile()).isNull();
        assertThat(view.winnerRounds()).isEmpty();
        assertThat(view.participantCount()).isZero();
        assertThat(view.roundCount()).isEqualTo(1);
        verify(participantRepo, never()).findAnswersWithQuestions(any(), any());
    }

    @Test
    void getSessionResults_revealWithPendingDecisions_winnerPresentOutcomeNull() {
        UUID finalistParticipantId = UUID.randomUUID();
        UUID finalistUserId = UUID.randomUUID();

        var decision = new FinalDecisionRow(UUID.randomUUID(), sessionId, finalistParticipantId,
                OffsetDateTime.now(), OffsetDateTime.now().plusHours(24),
                "PENDING", "PENDING", null, null, null, null);
        var finalist = new BlindDateParticipantRepository.ParticipantRow(
                finalistParticipantId, sessionId, finalistUserId, BlindDateConstants.PARTICIPANT_FINALIST,
                roundId, OffsetDateTime.now(), null, null, OffsetDateTime.now(), null, null);

        when(sessionRepo.findSession(sessionId))
                .thenReturn(Optional.of(sessionWithStatus(BlindDateConstants.SESSION_REVEAL)));
        when(finalDecisionRepo.findBySession(sessionId)).thenReturn(Optional.of(decision));
        when(sessionRepo.findRoundsForSession(sessionId)).thenReturn(List.of(
                new RoundRow(roundId, sessionId, 1, BlindDateConstants.ROUND_CLOSED,
                        OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now())));
        when(participantRepo.findParticipantsForSession(sessionId)).thenReturn(List.of(finalist));
        when(participantRepo.findParticipant(finalistParticipantId)).thenReturn(Optional.of(finalist));
        when(sessionRepo.findCreatorInfo(List.of(finalistUserId))).thenReturn(List.of(
                new CreatorInfoRow(finalistUserId, "Sara", "FEMALE", "ORTHODOX",
                        "MARRIAGE", 27, "Addis Ababa", "Ethiopia", null, null, null)));
        when(participantRepo.findAnswersWithQuestions(finalistParticipantId, roundId))
                .thenReturn(List.of());

        var view = service.getSessionResults(creatorId, sessionId);

        assertThat(view.finalist()).isNotNull();
        assertThat(view.decision().outcome()).isNull();
        assertThat(view.decision().matchId()).isNull();
        assertThat(view.profile().displayName()).isEqualTo("Sara");
        assertThat(view.profile().primaryPhoto()).isNull();
        assertThat(view.winnerRounds()).hasSize(1);
        assertThat(view.winnerRounds().get(0).answers()).isEmpty();
    }

    @Test
    void getSessionResults_missingFinalistRow_returnsNullWinner() {
        var decision = new FinalDecisionRow(UUID.randomUUID(), sessionId, UUID.randomUUID(),
                OffsetDateTime.now(), OffsetDateTime.now().plusHours(24),
                "PENDING", "PENDING", null, null, null, null);

        when(sessionRepo.findSession(sessionId))
                .thenReturn(Optional.of(sessionWithStatus(BlindDateConstants.SESSION_REVEAL)));
        when(finalDecisionRepo.findBySession(sessionId)).thenReturn(Optional.of(decision));
        when(sessionRepo.findRoundsForSession(sessionId)).thenReturn(List.of());
        when(participantRepo.findParticipantsForSession(sessionId)).thenReturn(List.of());
        when(participantRepo.findParticipant(any())).thenReturn(Optional.empty());

        var view = service.getSessionResults(creatorId, sessionId);

        assertThat(view.finalist()).isNull();
        assertThat(view.profile()).isNull();
        assertThat(view.winnerRounds()).isEmpty();
    }

    @Test
    void closeSession_success_closesRoundsAndEliminates() {
        UUID participantId = UUID.randomUUID();
        var participant = new BlindDateParticipantRepository.ParticipantRow(
                participantId, sessionId, UUID.randomUUID(), BlindDateConstants.PARTICIPANT_ACTIVE,
                roundId, OffsetDateTime.now(), null, null, null, null, null);

        when(sessionRepo.findSessionForUpdate(sessionId)).thenReturn(Optional.of(openSession()));
        when(participantRepo.findStillActiveInSession(sessionId)).thenReturn(List.of(participant));

        service.closeSession(creatorId, sessionId);

        verify(sessionRepo).closeOpenRoundsForSession(sessionId);
        verify(participantRepo).eliminateParticipant(participantId);
        verify(sessionRepo).closeSession(sessionId);
    }

    @Test
    void closeSession_inReveal_resolvesPendingDecisionAsNoMatch() {
        UUID finalistParticipantId = UUID.randomUUID();
        var decision = new FinalDecisionRow(UUID.randomUUID(), sessionId, finalistParticipantId,
                OffsetDateTime.now(), OffsetDateTime.now().plusHours(24),
                "INTERESTED", "PENDING", null, null, null, null);
        var finalist = new BlindDateParticipantRepository.ParticipantRow(
                finalistParticipantId, sessionId, UUID.randomUUID(),
                BlindDateConstants.PARTICIPANT_FINALIST,
                roundId, OffsetDateTime.now(), null, null, null, null, null);

        when(sessionRepo.findSessionForUpdate(sessionId))
                .thenReturn(Optional.of(sessionWithStatus(BlindDateConstants.SESSION_REVEAL)));
        when(participantRepo.findStillActiveInSession(sessionId)).thenReturn(List.of());
        when(finalDecisionRepo.findBySessionForUpdate(sessionId)).thenReturn(Optional.of(decision));
        when(participantRepo.findParticipant(finalistParticipantId)).thenReturn(Optional.of(finalist));

        service.closeSession(creatorId, sessionId);

        verify(finalDecisionRepo).resolvePendingAsNotInterested(sessionId);
        verify(finalDecisionRepo).setOutcome(sessionId, BlindDateConstants.OUTCOME_NO_MATCH, null);
        verify(sessionRepo).completeSession(sessionId);
        verify(sessionRepo, never()).closeSession(sessionId);
        verify(notificationDispatcher).dispatchBlindDateOutcomeNotification(
                creatorId, finalist.userId(), sessionId, false);
    }

    @Test
    void closeSession_terminalState_throwsSessionNotOpen() {
        when(sessionRepo.findSessionForUpdate(sessionId))
                .thenReturn(Optional.of(sessionWithStatus(BlindDateConstants.SESSION_COMPLETED)));

        assertThatThrownBy(() -> service.closeSession(creatorId, sessionId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("session_not_open");
    }
}
