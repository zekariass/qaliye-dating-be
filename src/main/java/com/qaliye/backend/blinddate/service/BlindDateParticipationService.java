package com.qaliye.backend.blinddate.service;

import com.qaliye.backend.blinddate.BlindDateConstants;
import com.qaliye.backend.blinddate.config.BlindDateProperties;
import com.qaliye.backend.blinddate.repository.BlindDateParticipantRepository;
import com.qaliye.backend.blinddate.repository.BlindDateParticipantRepository.AnswerRow;
import com.qaliye.backend.blinddate.repository.BlindDateParticipantRepository.ParticipantRow;
import com.qaliye.backend.blinddate.repository.BlindDateSessionRepository;
import com.qaliye.backend.blinddate.repository.BlindDateSessionRepository.RoundRow;
import com.qaliye.backend.blinddate.repository.BlindDateSessionRepository.SessionQuestionRow;
import com.qaliye.backend.blinddate.repository.BlindDateSessionRepository.SessionRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Participant-side flow: discovering open sessions, joining (paid), submitting
 * answers and withdrawing.
 */
@Service
public class BlindDateParticipationService {

    private final BlindDateSessionRepository sessionRepo;
    private final BlindDateParticipantRepository participantRepo;
    private final BlindDateChargeService chargeService;
    private final BlindDateProperties properties;

    public BlindDateParticipationService(BlindDateSessionRepository sessionRepo,
                                         BlindDateParticipantRepository participantRepo,
                                         BlindDateChargeService chargeService,
                                         BlindDateProperties properties) {
        this.sessionRepo = sessionRepo;
        this.participantRepo = participantRepo;
        this.chargeService = chargeService;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public List<BlindDateSessionRepository.DiscoverableSessionRow> discover(UUID callerId, int page, int size) {
        return sessionRepo.findDiscoverableSessions(callerId, Math.max(page, 0),
                Math.min(Math.max(size, 1), 50));
    }

    /**
     * Lists sessions the caller created or joined, with their participant row
     * when they joined. Powers {@code GET /sessions/mine}.
     */
    @Transactional(readOnly = true)
    public List<BlindDateSessionRepository.MySessionRow> getMySessions(UUID callerId, int page, int size) {
        return sessionRepo.findMySessions(callerId, Math.max(page, 0),
                Math.min(Math.max(size, 1), 50));
    }

    /**
     * Lists only the sessions the caller joined as a participant, newest join
     * first. Powers {@code GET /participations}.
     */
    @Transactional(readOnly = true)
    public List<BlindDateSessionRepository.MySessionRow> getMyParticipations(UUID callerId, int page, int size) {
        return sessionRepo.findMyParticipations(callerId, Math.max(page, 0),
                Math.min(Math.max(size, 1), 50));
    }

    /**
     * Round questions plus the caller's own saved answers
     * ({@code sessionQuestionId → answer}), so a returning participant can
     * resume where they left off. {@code myAnswers} is empty for the creator
     * or anyone who hasn't answered yet.
     */
    public record RoundQuestionsView(List<SessionQuestionRow> questions,
                                     Map<UUID, String> myAnswers) {}

    @Transactional(readOnly = true)
    public RoundQuestionsView getRoundQuestions(UUID callerId, UUID roundId) {
        List<SessionQuestionRow> questions = sessionRepo.findQuestionsForRound(roundId);
        Map<UUID, String> myAnswers = sessionRepo.findRound(roundId)
                .flatMap(r -> participantRepo.findBySessionAndUser(r.sessionId(), callerId))
                .map(p -> participantRepo.findAnswersForParticipantRound(p.id(), roundId).stream()
                        .collect(Collectors.toMap(AnswerRow::sessionQuestionId, AnswerRow::answer)))
                .orElse(Map.of());
        return new RoundQuestionsView(questions, myAnswers);
    }

    /**
     * Joins a session as a participant. Charges {@code BLIND_DATE_PARTICIPATE}
     * once per idempotency key.
     */
    @Transactional
    public ParticipantRow join(UUID userId, UUID sessionId, UUID idempotencyKey) {
        if (idempotencyKey == null) {
            // A user can only ever join a session once, so the natural key is
            // already idempotent — derive it when the client doesn't send one.
            idempotencyKey = UUID.nameUUIDFromBytes(
                    ("blind-date-join:" + sessionId + ":" + userId)
                            .getBytes(StandardCharsets.UTF_8));
        }

        var existing = participantRepo.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        SessionRow session = sessionRepo.findSessionForUpdate(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "session_not_found"));

        if (!BlindDateConstants.SESSION_OPEN.equals(session.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "session_not_open");
        }
        if (session.expiresAt() != null && session.expiresAt().isBefore(OffsetDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "session_expired");
        }
        if (session.creatorUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "creator_cannot_join");
        }
        if (participantRepo.hasParticipated(sessionId, userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "already_joined");
        }
        if (participantRepo.countStillActiveInSession(sessionId) >= properties.getMaxParticipants()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "session_full");
        }

        RoundRow openRound = sessionRepo.findOpenRound(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "no_open_round"));
        if (openRound.roundNumber() != 1) {
            // Participants can only join at round 1.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "join_window_closed");
        }

        chargeService.charge(userId, BlindDateConstants.ACTION_PARTICIPATE,
                "blind-date-participate:" + idempotencyKey);

        try {
            UUID participantId = participantRepo.insertParticipant(sessionId, userId,
                    openRound.id(), idempotencyKey);
            return participantRepo.findParticipant(participantId).orElseThrow();
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "already_joined");
        }
    }

    /**
     * Submits or updates the participant's answers for their current round.
     * Answers are locked once the creator has made a selection for that
     * participant in that round.
     */
    @Transactional
    public void submitAnswers(UUID userId, UUID participantId, Map<UUID, String> answers) {
        ParticipantRow participant = participantRepo.findParticipantForUpdate(participantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "participant_not_found"));

        if (!participant.userId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not_participant");
        }
        if (!BlindDateConstants.PARTICIPANT_ACTIVE.equals(participant.status())
                && !BlindDateConstants.PARTICIPANT_ADVANCED.equals(participant.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "participant_not_active");
        }
        if (participant.currentRoundId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no_current_round");
        }

        RoundRow round = sessionRepo.findRound(participant.currentRoundId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "round_not_found"));
        if (!BlindDateConstants.ROUND_OPEN.equals(round.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "round_closed");
        }

        List<SessionQuestionRow> roundQuestions = sessionRepo.findQuestionsForRound(round.id());
        Map<UUID, SessionQuestionRow> byId = new java.util.HashMap<>();
        for (SessionQuestionRow q : roundQuestions) {
            byId.put(q.id(), q);
        }

        for (Map.Entry<UUID, String> entry : answers.entrySet()) {
            UUID sessionQuestionId = entry.getKey();
            String answer = entry.getValue();

            if (!byId.containsKey(sessionQuestionId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question_not_in_round");
            }
            if (answer == null || answer.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "answer_required");
            }
            if (answer.trim().length() > BlindDateConstants.MAX_ANSWER_LENGTH) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "answer_too_long");
            }
            if (!participantRepo.isAnswerEditable(participantId, sessionQuestionId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "answer_locked");
            }
            participantRepo.upsertAnswer(participantId, sessionQuestionId, answer.trim());
        }
    }

    /**
     * Withdraws a participant from the session. Allowed while ACTIVE, ADVANCED,
     * FINALIST or REVEALED (a finalist withdrawing during REVEAL forfeits).
     */
    @Transactional
    public void withdraw(UUID userId, UUID participantId) {
        ParticipantRow participant = participantRepo.findParticipantForUpdate(participantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "participant_not_found"));

        if (!participant.userId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not_participant");
        }
        if (!participantRepo.withdrawParticipant(participantId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "cannot_withdraw");
        }
    }
}
