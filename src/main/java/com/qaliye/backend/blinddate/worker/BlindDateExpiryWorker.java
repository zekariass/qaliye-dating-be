package com.qaliye.backend.blinddate.worker;

import com.qaliye.backend.blinddate.repository.BlindDateFinalDecisionRepository;
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
    private BlindDateFinalDecisionRepository finalDecisionRepo;

    @Autowired
    private BlindDateFinalDecisionService finalDecisionService;

    @Override
    public void execute(JobExecutionContext context) {
        try {
            List<UUID> expiredSessions = sessionRepo.expireOpenSessions();
            if (!expiredSessions.isEmpty()) {
                log.info("BlindDateExpiry: expired {} open sessions", expiredSessions.size());
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
