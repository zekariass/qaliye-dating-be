package com.qaliye.backend.blinddate.service;

import com.qaliye.backend.blinddate.BlindDateConstants;
import com.qaliye.backend.blinddate.config.BlindDateProperties;
import com.qaliye.backend.blinddate.repository.BlindDateCatalogRepository;
import com.qaliye.backend.blinddate.repository.BlindDateFinalDecisionRepository;
import com.qaliye.backend.blinddate.repository.BlindDateFinalDecisionRepository.FinalDecisionRow;
import com.qaliye.backend.blinddate.repository.BlindDateParticipantRepository;
import com.qaliye.backend.blinddate.repository.BlindDateParticipantRepository.ParticipantAnswerView;
import com.qaliye.backend.blinddate.repository.BlindDateParticipantRepository.ParticipantRow;
import com.qaliye.backend.blinddate.repository.BlindDateParticipantRepository.SelectionRow;
import com.qaliye.backend.blinddate.repository.BlindDateQuestionSetRepository;
import com.qaliye.backend.blinddate.repository.BlindDateQuestionSetRepository.CustomQuestionRow;
import com.qaliye.backend.blinddate.repository.BlindDateQuestionSetRepository.SetQuestionRow;
import com.qaliye.backend.blinddate.repository.BlindDateSessionRepository;
import com.qaliye.backend.blinddate.repository.BlindDateSessionRepository.CreatorInfoRow;
import com.qaliye.backend.blinddate.repository.BlindDateSessionRepository.RoundRow;
import com.qaliye.backend.blinddate.repository.BlindDateSessionRepository.SessionQuestionRow;
import com.qaliye.backend.blinddate.repository.BlindDateSessionRepository.SessionRow;
import com.qaliye.backend.discovery.dto.DiscoveryPhotoDto;
import com.qaliye.backend.discovery.service.StorageSigningService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Session lifecycle: creation (paid), round creation with question snapshots,
 * and close/cancel.
 */
@Service
public class BlindDateSessionService {

    public record SessionView(SessionRow session, List<RoundRow> rounds,
                              int participantCount, int currentRoundNumber) {}

    /** A participant plus their answers to the session's current open round,
     * and the creator's recorded selection decision for that round (if any). */
    public record ParticipantReviewView(ParticipantRow participant,
                                        List<ParticipantAnswerView> answers,
                                        String decision) {}

    /**
     * Public creator info participants see before joining: primary photo
     * (client blurs it), location, religion, intention and gender.
     * {@code displayName} is loaded for creator-facing views (e.g. session
     * results) — keep it out of participant-facing payloads until reveal.
     */
    public record CreatorInfo(UUID userId, String displayName, String gender,
                              String religion, String relationshipIntention, Integer age,
                              String city, String countryName,
                              DiscoveryPhotoDto primaryPhoto) {}

    /** One session round plus the finalist's answers to it. */
    public record RoundAnswersView(RoundRow round, List<ParticipantAnswerView> answers) {}

    /**
     * Creator-facing session results: outcome, the revealed finalist with
     * their public profile, and the finalist's answers grouped by round.
     * {@code finalist} and {@code profile} are null when no finalist was
     * selected (or the row is missing); {@code decision} is null when no
     * final-decision row exists yet.
     */
    public record SessionResultsView(SessionRow session, FinalDecisionRow decision,
                                     ParticipantRow finalist, CreatorInfo profile,
                                     int participantCount, int roundCount,
                                     List<RoundAnswersView> winnerRounds) {}

    private final BlindDateSessionRepository sessionRepo;
    private final BlindDateParticipantRepository participantRepo;
    private final BlindDateQuestionSetRepository questionSetRepo;
    private final BlindDateCatalogRepository catalogRepo;
    private final BlindDateFinalDecisionRepository finalDecisionRepo;
    private final BlindDateChargeService chargeService;
    private final BlindDateProperties properties;
    private final StorageSigningService signingService;

    public BlindDateSessionService(BlindDateSessionRepository sessionRepo,
                                   BlindDateParticipantRepository participantRepo,
                                   BlindDateQuestionSetRepository questionSetRepo,
                                   BlindDateCatalogRepository catalogRepo,
                                   BlindDateFinalDecisionRepository finalDecisionRepo,
                                   BlindDateChargeService chargeService,
                                   BlindDateProperties properties,
                                   StorageSigningService signingService) {
        this.sessionRepo = sessionRepo;
        this.participantRepo = participantRepo;
        this.questionSetRepo = questionSetRepo;
        this.catalogRepo = catalogRepo;
        this.finalDecisionRepo = finalDecisionRepo;
        this.chargeService = chargeService;
        this.properties = properties;
        this.signingService = signingService;
    }

    /**
     * Creates a session and its first round, snapshotting the chosen questions.
     * Charges {@code BLIND_DATE_SESSION_CREATE} once per idempotency key.
     *
     * @param questionIds      platform question ids from the creator's set (may be empty)
     * @param customQuestionIds custom question ids from the creator's set (may be empty)
     */
    @Transactional
    public SessionView createSession(UUID creatorId, UUID idempotencyKey,
                                     List<UUID> questionIds, List<UUID> customQuestionIds,
                                     String languageCode, OffsetDateTime expiresAt) {
        if (idempotencyKey == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "idempotency_key_required");
        }

        // Idempotent replay: same key returns the existing session.
        var existing = sessionRepo.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return toView(existing.get());
        }

        String language = languageCode != null ? languageCode
                : questionSetRepo.findConfiguration(creatorId)
                        .map(BlindDateQuestionSetRepository.ConfigurationRow::languageCode)
                        .orElse("en");
        if (!catalogRepo.isSupportedLanguage(language)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported_language");
        }

        int totalQuestions = (questionIds != null ? questionIds.size() : 0)
                + (customQuestionIds != null ? customQuestionIds.size() : 0);
        if (totalQuestions < BlindDateConstants.MIN_ROUND_QUESTIONS
                || totalQuestions > properties.getMaxRoundQuestions()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_question_count");
        }

        // Charge before creating anything; a retried request with the same key
        // short-circuits above so it never double-charges.
        chargeService.charge(creatorId, BlindDateConstants.ACTION_SESSION_CREATE,
                "blind-date-session:" + idempotencyKey);

        UUID sessionId;
        try {
            sessionId = sessionRepo.insertSession(creatorId, language, expiresAt, idempotencyKey);
        } catch (DuplicateKeyException e) {
            // One active session per creator (partial unique index).
            throw new ResponseStatusException(HttpStatus.CONFLICT, "active_session_exists");
        }

        UUID roundId = sessionRepo.insertRound(sessionId, 1);
        snapshotQuestions(creatorId, sessionId, roundId, language, questionIds, customQuestionIds);

        return toView(sessionRepo.findSession(sessionId).orElseThrow());
    }

    /**
     * Snapshots the selected set/custom questions into the new round. Every
     * snapshotted question must belong to the creator's set and have an answer.
     */
    private void snapshotQuestions(UUID creatorId, UUID sessionId, UUID roundId, String language,
                                   List<UUID> questionIds, List<UUID> customQuestionIds) {
        UUID setId = questionSetRepo.ensureQuestionSet(creatorId);
        int sortOrder = 1;

        if (questionIds != null) {
            for (UUID questionId : questionIds) {
                SetQuestionRow setQuestion = questionSetRepo
                        .findSetQuestions(setId, language, false).stream()
                        .filter(q -> q.questionId().equals(questionId))
                        .findFirst()
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "question_not_in_set"));
                if (setQuestion.answer() == null || setQuestion.answer().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question_unanswered");
                }
                sessionRepo.insertSessionQuestion(sessionId, roundId, questionId, null,
                        setQuestion.questionText(), setQuestion.answer(), language, sortOrder++);
            }
        }

        if (customQuestionIds != null) {
            for (UUID customId : customQuestionIds) {
                CustomQuestionRow custom = questionSetRepo
                        .findCustomQuestions(setId, false).stream()
                        .filter(q -> q.id().equals(customId))
                        .findFirst()
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "custom_question_not_in_set"));
                sessionRepo.insertSessionQuestion(sessionId, roundId, null, customId,
                        custom.question(), custom.answer(), language, sortOrder++);
            }
        }
    }

    /**
     * Creates the next round for a session, moving ADVANCED participants into it
     * and snapshotting the chosen questions. Only the creator may do this while
     * the session is OPEN and no round is currently open.
     */
    @Transactional
    public RoundRow createNextRound(UUID creatorId, UUID sessionId,
                                    List<UUID> questionIds, List<UUID> customQuestionIds) {
        SessionRow session = sessionRepo.findSessionForUpdate(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "session_not_found"));
        requireCreator(session, creatorId);
        requireStatus(session, BlindDateConstants.SESSION_OPEN);

        if (sessionRepo.findOpenRound(sessionId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "round_still_open");
        }
        if (sessionRepo.findLatestRoundNumber(sessionId) >= properties.getMaxRounds()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "max_rounds_reached");
        }

        int totalQuestions = (questionIds != null ? questionIds.size() : 0)
                + (customQuestionIds != null ? customQuestionIds.size() : 0);
        if (totalQuestions < BlindDateConstants.MIN_ROUND_QUESTIONS
                || totalQuestions > properties.getMaxRoundQuestions()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_question_count");
        }

        int nextRoundNumber = sessionRepo.findLatestRoundNumber(sessionId) + 1;
        UUID newRoundId = sessionRepo.insertRound(sessionId, nextRoundNumber);
        snapshotQuestions(creatorId, sessionId, newRoundId, session.languageCode(),
                questionIds, customQuestionIds);

        // Move survivors of the previous round into the new round.
        participantRepo.findStillActiveInSession(sessionId).stream()
                .filter(p -> BlindDateConstants.PARTICIPANT_ADVANCED.equals(p.status()))
                .forEach(p -> participantRepo.advanceParticipant(p.id(), newRoundId));

        return sessionRepo.findRound(newRoundId).orElseThrow();
    }

    /**
     * Closes a session early (creator only). Open rounds are closed and
     * still-active participants are eliminated.
     */
    @Transactional
    public void closeSession(UUID creatorId, UUID sessionId) {
        SessionRow session = sessionRepo.findSessionForUpdate(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "session_not_found"));
        requireCreator(session, creatorId);
        requireStatus(session, BlindDateConstants.SESSION_OPEN);

        sessionRepo.closeOpenRoundsForSession(sessionId);
        participantRepo.findStillActiveInSession(sessionId)
                .forEach(p -> participantRepo.eliminateParticipant(p.id()));
        sessionRepo.closeSession(sessionId);
    }

    @Transactional(readOnly = true)
    public SessionView getSession(UUID sessionId) {
        SessionRow session = sessionRepo.findSession(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "session_not_found"));
        return toView(session);
    }

    @Transactional(readOnly = true)
    public List<SessionQuestionRow> getRoundQuestions(UUID roundId) {
        return sessionRepo.findQuestionsForRound(roundId);
    }

    /**
     * Creator-only roster of a session's participants with their answers to
     * the current open round. Powers {@code GET /sessions/{id}/participants}.
     * Participant user ids are intentionally not exposed — the game is blind.
     */
    @Transactional(readOnly = true)
    public List<ParticipantReviewView> getSessionParticipants(UUID callerId, UUID sessionId) {
        SessionRow session = sessionRepo.findSession(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "session_not_found"));
        requireCreator(session, callerId);

        UUID openRoundId = sessionRepo.findOpenRound(sessionId).map(RoundRow::id).orElse(null);
        // ADVANCE is only recorded as a selection — the participant's status
        // stays ACTIVE until the round closes. Surface the recorded decision so
        // the creator can see who they've already marked.
        Map<UUID, String> decisions = openRoundId != null
                ? participantRepo.findSelectionsForRound(openRoundId).stream()
                        .collect(Collectors.toMap(SelectionRow::participantId,
                                SelectionRow::decision, (a, b) -> b))
                : Map.of();
        List<ParticipantReviewView> views = new ArrayList<>();
        for (ParticipantRow p : participantRepo.findParticipantsForSession(sessionId)) {
            List<ParticipantAnswerView> answers = openRoundId != null
                    ? participantRepo.findAnswersWithQuestions(p.id(), openRoundId)
                    : List.of();
            views.add(new ParticipantReviewView(p, answers, decisions.get(p.id())));
        }
        return views;
    }

    /**
     * Creator-only results view for a finished (or in-progress) session:
     * the outcome, the finalist's revealed identity and profile, and the
     * finalist's answers grouped by round. The finalist's {@code user_id}
     * is intentionally exposed here only — the reveal already happened once
     * a finalist exists. Sessions without a final-decision row (closed,
     * cancelled or expired before {@code SELECT_FINALIST}) return
     * {@code winner: null} — this is not an error.
     */
    @Transactional(readOnly = true)
    public SessionResultsView getSessionResults(UUID callerId, UUID sessionId) {
        SessionRow session = sessionRepo.findSession(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "session_not_found"));
        requireCreator(session, callerId);

        FinalDecisionRow decision = finalDecisionRepo.findBySession(sessionId).orElse(null);
        List<RoundRow> rounds = sessionRepo.findRoundsForSession(sessionId);
        int participantCount = participantRepo.findParticipantsForSession(sessionId).size();

        ParticipantRow finalist = null;
        CreatorInfo profile = null;
        List<RoundAnswersView> winnerRounds = List.of();

        if (decision != null && decision.finalistParticipantId() != null) {
            finalist = participantRepo.findParticipant(decision.finalistParticipantId()).orElse(null);
        }
        if (finalist != null) {
            profile = getCreatorInfos(List.of(finalist.userId())).get(finalist.userId());
            List<RoundAnswersView> perRound = new ArrayList<>(rounds.size());
            for (RoundRow round : rounds) {
                perRound.add(new RoundAnswersView(round,
                        participantRepo.findAnswersWithQuestions(finalist.id(), round.id())));
            }
            winnerRounds = perRound;
        }

        return new SessionResultsView(session, decision, finalist, profile,
                participantCount, rounds.size(), winnerRounds);
    }

    /**
     * Batch-loads public creator info (photo signed URL, city, religion,
     * intention, gender) for the given user ids. Used to decorate session
     * payloads so participants can decide whether to join.
     */
    @Transactional(readOnly = true)
    public Map<UUID, CreatorInfo> getCreatorInfos(Collection<UUID> userIds) {
        Map<UUID, CreatorInfo> result = new LinkedHashMap<>();
        for (CreatorInfoRow row : sessionRepo.findCreatorInfo(userIds)) {
            DiscoveryPhotoDto photo = row.photoId() != null
                    ? signingService.signPhoto(row.photoId(), 0, true,
                            row.photoBucket(), row.photoPath())
                    : null;
            result.put(row.userId(), new CreatorInfo(row.userId(), row.displayName(), row.gender(),
                    row.religion(), row.relationshipIntention(), row.age(),
                    row.city(), row.countryName(), photo));
        }
        return result;
    }

    private SessionView toView(SessionRow session) {
        List<RoundRow> rounds = sessionRepo.findRoundsForSession(session.id());
        int currentRoundNumber = rounds.stream().mapToInt(RoundRow::roundNumber).max().orElse(0);
        return new SessionView(session, rounds,
                participantRepo.countStillActiveInSession(session.id()), currentRoundNumber);
    }

    private void requireCreator(SessionRow session, UUID userId) {
        if (!session.creatorUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not_session_creator");
        }
    }

    private void requireStatus(SessionRow session, String expected) {
        if (!expected.equals(session.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "session_not_" + expected.toLowerCase());
        }
    }
}
