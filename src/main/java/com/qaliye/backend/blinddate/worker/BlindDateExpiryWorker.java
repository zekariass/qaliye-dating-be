package com.qaliye.backend.blinddate.worker;

import com.qaliye.backend.blinddate.repository.BlindDateFinalDecisionRepository;
import com.qaliye.backend.blinddate.repository.BlindDateParticipantRepository;
import com.qaliye.backend.blinddate.repository.BlindDateSessionRepository;
import com.qaliye.backend.blinddate.service.BlindDateFinalDecisionService;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Periodic sweeper for Blind Date deadlines:
 * <ul>
 *   <li>OPEN sessions whose {@code expires_at} has passed → EXPIRED</li>
 *   <li>Final-decision windows whose deadline passed → resolved as EXPIRED</li>
 * </ul>
 */
@Component
public class BlindDateExpiryWorker implements Job {

    private static final Logger log = LoggerFactory.getLogger(BlindDateExpiryWorker.class);

    @Autowired
    private BlindDateSessionRepository sessionRepo;

    @Autowired
    private BlindDateParticipantRepository participantRepo;

    @Autowired
    private BlindDateFinalDecisionRepository finalDecisionRepo;

    @Autowired
    private BlindDateFinalDecisionService finalDecisionService;

    @Override
    public void execute(JobExecutionContext context) {
        try {
            List<UUID> expiredSessions = sessionRepo.expireOpenSessions();
            for (UUID sessionId : expiredSessions) {
                try {
                    // No orphans: close the open round and eliminate anyone
                    // still ACTIVE/ADVANCED on the dead session.
                    sessionRepo.closeOpenRoundsForSession(sessionId);
                    participantRepo.findStillActiveInSession(sessionId)
                            .forEach(p -> participantRepo.eliminateParticipant(p.id()));
                } catch (Exception e) {
                    log.error("BlindDateExpiry: failed to clean up expired session {}: {}",
                            sessionId, e.getMessage(), e);
                }
            }
            if (!expiredSessions.isEmpty()) {
                log.info("BlindDateExpiry: expired {} open sessions", expiredSessions.size());
            }

            // Self-healing sweep: catches participants who joined in the narrow
            // window between session expiry and the per-session cleanup above.
            List<UUID> orphaned = participantRepo.eliminateStillActiveInNonOpenSessions();
            if (!orphaned.isEmpty()) {
                log.info("BlindDateExpiry: eliminated {} participants on non-open sessions",
                        orphaned.size());
            }

            var expiredDecisions = finalDecisionRepo.findExpiredPending();
            for (var fd : expiredDecisions) {
                try {
                    finalDecisionService.expireFinalDecision(fd);
                } catch (Exception e) {
                    log.error("BlindDateExpiry: failed to expire final decision for session {}: {}",
                            fd.sessionId(), e.getMessage(), e);
                }
            }
            if (!expiredDecisions.isEmpty()) {
                log.info("BlindDateExpiry: resolved {} expired final decisions", expiredDecisions.size());
            }
        } catch (Exception e) {
            log.error("BlindDateExpiry error: {}", e.getMessage(), e);
        }
    }
}
