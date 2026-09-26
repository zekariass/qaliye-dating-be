package com.qaliye.backend.blinddate.service;

import com.qaliye.backend.blinddate.BlindDateConstants;
import com.qaliye.backend.blinddate.repository.BlindDateFinalDecisionRepository;
import com.qaliye.backend.blinddate.repository.BlindDateFinalDecisionRepository.FinalDecisionRow;
import com.qaliye.backend.blinddate.repository.BlindDateFinalDecisionRepository.FinalDecisionWithFinalist;
import com.qaliye.backend.blinddate.repository.BlindDateParticipantRepository;
import com.qaliye.backend.blinddate.repository.BlindDateParticipantRepository.ParticipantRow;
import com.qaliye.backend.blinddate.repository.BlindDateSessionRepository;
import com.qaliye.backend.blinddate.repository.BlindDateSessionRepository.SessionRow;
import com.qaliye.backend.notifications.NotificationDispatcher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The post-reveal mutual decision. Both the creator and the revealed finalist
 * submit INTERESTED / NOT_INTERESTED; when both have decided the outcome is
 * resolved (MATCHED / NO_MATCH / ALREADY_MATCHED) and the session completes.
 */
@Service
public class BlindDateFinalDecisionService {

    private final BlindDateSessionRepository sessionRepo;
    private final BlindDateParticipantRepository participantRepo;
    private final BlindDateFinalDecisionRepository finalDecisionRepo;
    private final BlindDateMatchService matchService;
    private final NotificationDispatcher notificationDispatcher;

    public BlindDateFinalDecisionService(BlindDateSessionRepository sessionRepo,
                                         BlindDateParticipantRepository participantRepo,
                                         BlindDateFinalDecisionRepository finalDecisionRepo,
                                         BlindDateMatchService matchService,
                                         NotificationDispatcher notificationDispatcher) {
        this.sessionRepo = sessionRepo;
        this.participantRepo = participantRepo;
        this.finalDecisionRepo = finalDecisionRepo;
        this.matchService = matchService;
        this.notificationDispatcher = notificationDispatcher;
    }

    /**
     * Submits the caller's final decision. The caller must be either the
     * session creator or the revealed finalist.
     */
    @Transactional
    public FinalDecisionRow decide(UUID userId, UUID sessionId, String decision) {
        if (!BlindDateConstants.FINAL_INTERESTED.equals(decision)
                && !BlindDateConstants.FINAL_NOT_INTERESTED.equals(decision)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_decision");
        }

        SessionRow session = sessionRepo.findSessionForUpdate(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "session_not_found"));
        if (!BlindDateConstants.SESSION_REVEAL.equals(session.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "session_not_in_reveal");
        }

        FinalDecisionRow fd = finalDecisionRepo.findBySessionForUpdate(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "no_final_decision"));
        if (fd.outcome() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "decision_already_resolved");
        }

        ParticipantRow finalist = participantRepo.findParticipant(fd.finalistParticipantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "finalist_not_found"));

        boolean isCreator = session.creatorUserId().equals(userId);
        boolean isFinalist = finalist.userId().equals(userId);
        if (!isCreator && !isFinalist) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not_a_decision_party");
        }

        if (isCreator) {
            if (!BlindDateConstants.FINAL_PENDING.equals(fd.creatorDecision())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "decision_already_submitted");
            }
            finalDecisionRepo.setCreatorDecision(sessionId, decision);
        } else {
            if (!BlindDateConstants.FINAL_PENDING.equals(fd.participantDecision())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "decision_already_submitted");
            }
            finalDecisionRepo.setParticipantDecision(sessionId, decision);
        }

        FinalDecisionRow updated = finalDecisionRepo.findBySession(sessionId).orElseThrow();
        resolveIfComplete(session, finalist, updated);
        return finalDecisionRepo.findBySession(sessionId).orElseThrow();
    }

    /**
     * Per-caller view of a session's final decision. The other party's pending
     * decision is intentionally hidden (only a {@code decided} flag) so neither
     * side can see the answer before committing their own.
     */
    public record FinalDecisionView(String myDecision, boolean otherPartyDecided,
                                    String outcome, UUID matchId,
                                    OffsetDateTime revealedAt, OffsetDateTime decisionDeadlineAt) {}

    /**
     * Batch-loads final-decision views for the given sessions, resolved
     * against {@code callerId} (creator or finalist). Sessions without a
     * final-decision row are absent from the map.
     */
    @Transactional(readOnly = true)
    public Map<UUID, FinalDecisionView> getFinalDecisions(UUID callerId, Collection<UUID> sessionIds,
                                                        Map<UUID, UUID> sessionCreators) {
        Map<UUID, FinalDecisionView> result = new LinkedHashMap<>();
        for (FinalDecisionWithFinalist row : finalDecisionRepo.findBySessionIds(sessionIds)) {
            FinalDecisionRow fd = row.decision();
            UUID creatorId = sessionCreators.get(fd.sessionId());
            boolean isCreator = callerId.equals(creatorId);
            boolean isFinalist = callerId.equals(row.finalistUserId());

            String myDecision = isCreator ? fd.creatorDecision()
                    : isFinalist ? fd.participantDecision() : null;
            boolean otherPartyDecided = isCreator
                    ? !BlindDateConstants.FINAL_PENDING.equals(fd.participantDecision())
                    : isFinalist && !BlindDateConstants.FINAL_PENDING.equals(fd.creatorDecision());

            result.put(fd.sessionId(), new FinalDecisionView(myDecision, otherPartyDecided,
                    fd.outcome(), fd.matchId(), fd.revealedAt(), fd.decisionDeadlineAt()));
        }
        return result;
    }

    /**
     * Resolves the outcome once both sides have decided. A single
     * NOT_INTERESTED short-circuits to NO_MATCH; mutual INTERESTED attempts the
     * match (which may still come back ALREADY_MATCHED or blocked).
     */
    private void resolveIfComplete(SessionRow session, ParticipantRow finalist, FinalDecisionRow fd) {
        boolean creatorDone = !BlindDateConstants.FINAL_PENDING.equals(fd.creatorDecision());
        boolean participantDone = !BlindDateConstants.FINAL_PENDING.equals(fd.participantDecision());
        if (!creatorDone || !participantDone) {
            return;
        }

        boolean bothInterested = BlindDateConstants.FINAL_INTERESTED.equals(fd.creatorDecision())
                && BlindDateConstants.FINAL_INTERESTED.equals(fd.participantDecision());

        if (!bothInterested) {
            finalDecisionRepo.setOutcome(session.id(), BlindDateConstants.OUTCOME_NO_MATCH, null);
            sessionRepo.completeSession(session.id());
            notificationDispatcher.dispatchBlindDateOutcomeNotification(
                    session.creatorUserId(), finalist.userId(), session.id(), false);
            return;
        }

        var result = matchService.createBlindDateMatch(
                session.creatorUserId(), finalist.userId(), session.id());
        if (result.matched()) {
            finalDecisionRepo.setOutcome(session.id(), BlindDateConstants.OUTCOME_MATCHED, result.matchId());
            sessionRepo.completeSession(session.id());
            notificationDispatcher.dispatchBlindDateOutcomeNotification(
                    session.creatorUserId(), finalist.userId(), session.id(), true);
        } else if (result.matchId() != null) {
            finalDecisionRepo.setOutcome(session.id(), BlindDateConstants.OUTCOME_ALREADY_MATCHED, result.matchId());
            sessionRepo.completeSession(session.id());
            notificationDispatcher.dispatchBlindDateOutcomeNotification(
                    session.creatorUserId(), finalist.userId(), session.id(), false);
        } else {
            // Blocked — no match possible.
            finalDecisionRepo.setOutcome(session.id(), BlindDateConstants.OUTCOME_NO_MATCH, null);
            sessionRepo.completeSession(session.id());
            notificationDispatcher.dispatchBlindDateOutcomeNotification(
                    session.creatorUserId(), finalist.userId(), session.id(), false);
        }
    }

    /**
     * Expires a final-decision window: any side that never decided is treated
     * as NOT_INTERESTED and the outcome resolves to NO_MATCH.
     */
    @Transactional
    public void expireFinalDecision(FinalDecisionRow fd) {
        SessionRow session = sessionRepo.findSessionForUpdate(fd.sessionId()).orElse(null);
        if (session == null || fd.outcome() != null) {
            return;
        }
        ParticipantRow finalist = participantRepo.findParticipant(fd.finalistParticipantId()).orElse(null);
        if (finalist == null) {
            return;
        }
        finalDecisionRepo.resolvePendingAsNotInterested(fd.sessionId());
        finalDecisionRepo.setOutcome(fd.sessionId(), BlindDateConstants.OUTCOME_EXPIRED, null);
        sessionRepo.completeSession(fd.sessionId());
        notificationDispatcher.dispatchBlindDateOutcomeNotification(
                session.creatorUserId(), finalist.userId(), fd.sessionId(), false);
    }
}
