package com.qaliye.backend.blinddate.service;

import com.qaliye.backend.blinddate.BlindDateConstants;
import com.qaliye.backend.blinddate.config.BlindDateProperties;
import com.qaliye.backend.blinddate.repository.BlindDateFinalDecisionRepository;
import com.qaliye.backend.blinddate.repository.BlindDateParticipantRepository;
import com.qaliye.backend.blinddate.repository.BlindDateParticipantRepository.ParticipantRow;
import com.qaliye.backend.blinddate.repository.BlindDateSessionRepository;
import com.qaliye.backend.blinddate.repository.BlindDateSessionRepository.RoundRow;
import com.qaliye.backend.blinddate.repository.BlindDateSessionRepository.SessionRow;
import com.qaliye.backend.notifications.NotificationDispatcher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The creator's per-participant decisions inside a round: ADVANCE, ELIMINATE or
 * SELECT_FINALIST. Selecting a finalist atomically closes the round, eliminates
 * everyone else, promotes the finalist and moves the session to REVEAL.
 */
@Service
public class BlindDateSelectionService {

    private final BlindDateSessionRepository sessionRepo;
    private final BlindDateParticipantRepository participantRepo;
    private final BlindDateFinalDecisionRepository finalDecisionRepo;
    private final NotificationDispatcher notificationDispatcher;
    private final BlindDateProperties properties;

    public BlindDateSelectionService(BlindDateSessionRepository sessionRepo,
                                     BlindDateParticipantRepository participantRepo,
                                     BlindDateFinalDecisionRepository finalDecisionRepo,
                                     NotificationDispatcher notificationDispatcher,
                                     BlindDateProperties properties) {
        this.sessionRepo = sessionRepo;
        this.participantRepo = participantRepo;
        this.finalDecisionRepo = finalDecisionRepo;
        this.notificationDispatcher = notificationDispatcher;
        this.properties = properties;
    }

    /**
     * Records the creator's decision for one participant in the session's open
     * round. SELECT_FINALIST additionally performs the reveal transition.
     */
    @Transactional
    public void select(UUID creatorId, UUID sessionId, UUID participantId, String decision) {
        if (!List.of(BlindDateConstants.DECISION_ADVANCE,
                BlindDateConstants.DECISION_ELIMINATE,
                BlindDateConstants.DECISION_SELECT_FINALIST).contains(decision)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_decision");
        }

        SessionRow session = sessionRepo.findSessionForUpdate(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "session_not_found"));
        if (!session.creatorUserId().equals(creatorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not_session_creator");
        }
        if (!BlindDateConstants.SESSION_OPEN.equals(session.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "session_not_open");
        }

        RoundRow round = sessionRepo.findOpenRound(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "no_open_round"));

        ParticipantRow participant = participantRepo.findParticipantForUpdate(participantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "participant_not_found"));
        if (!participant.sessionId().equals(sessionId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "participant_not_in_session");
        }
        if (!BlindDateConstants.PARTICIPANT_ACTIVE.equals(participant.status())
                && !BlindDateConstants.PARTICIPANT_ADVANCED.equals(participant.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "participant_not_active");
        }
        if (!round.id().equals(participant.currentRoundId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "participant_not_in_round");
        }

        participantRepo.upsertSelection(round.id(), participantId, creatorId, decision);

        switch (decision) {
            case BlindDateConstants.DECISION_ELIMINATE -> {
                participantRepo.eliminateParticipant(participantId);
                notificationDispatcher.dispatchBlindDateEliminatedNotification(
                        participant.userId(), sessionId);
            }
            case BlindDateConstants.DECISION_SELECT_FINALIST ->
                    transitionToReveal(session, round, participant);
            default -> { /* ADVANCE: participant stays until round closes */ }
        }
    }

    /**
     * Closes the current round. Participants the creator marked ADVANCE move to
     * ADVANCED (awaiting the next round); everyone still undecided is eliminated.
     * The creator then opens the next round via {@code createNextRound}.
     */
    @Transactional
    public void closeRound(UUID creatorId, UUID sessionId) {
        SessionRow session = sessionRepo.findSessionForUpdate(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "session_not_found"));
        if (!session.creatorUserId().equals(creatorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not_session_creator");
        }
        if (!BlindDateConstants.SESSION_OPEN.equals(session.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "session_not_open");
        }

        RoundRow round = sessionRepo.findOpenRound(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "no_open_round"));

        List<ParticipantRow> inRound = participantRepo.findActiveParticipantsInRound(round.id());
        var selections = participantRepo.findSelectionsForRound(round.id());
        var advancedIds = selections.stream()
                .filter(s -> BlindDateConstants.DECISION_ADVANCE.equals(s.decision()))
                .map(s -> s.participantId())
                .collect(java.util.stream.Collectors.toSet());

        for (ParticipantRow p : inRound) {
            if (advancedIds.contains(p.id())) {
                participantRepo.advanceParticipant(p.id(), round.id());
            } else {
                participantRepo.eliminateParticipant(p.id());
                notificationDispatcher.dispatchBlindDateEliminatedNotification(p.userId(), sessionId);
            }
        }

        sessionRepo.closeRound(round.id());
    }

    /**
     * Atomic reveal transition: close the round, eliminate every other
     * still-active participant, promote the finalist, move the session to
     * REVEAL and open the final-decision window.
     */
    private void transitionToReveal(SessionRow session, RoundRow round, ParticipantRow finalist) {
        sessionRepo.closeRound(round.id());
        participantRepo.eliminateAllStillActiveExcept(session.id(), finalist.id());
        participantRepo.promoteToFinalist(finalist.id());
        participantRepo.markRevealed(finalist.id());
        sessionRepo.transitionToReveal(session.id());
        finalDecisionRepo.insert(session.id(), finalist.id(),
                OffsetDateTime.now().plusHours(properties.getDecisionWindowHours()));
        notificationDispatcher.dispatchBlindDateRevealNotification(
                session.creatorUserId(), finalist.userId(), session.id());
    }
}
