package com.qaliye.backend.matchmaking.worker;

import com.qaliye.backend.matchmaking.service.MatchmakingIntroductionService;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Quartz sweeper: expires PROPOSED introductions whose decision window has closed
 * and releases both requests back to OPEN status.
 *
 * <p>Runs every 5 minutes.
 */
@Component
public class MatchmakingIntroductionExpiryWorker implements Job {

    private static final Logger log = LoggerFactory.getLogger(MatchmakingIntroductionExpiryWorker.class);

    @Autowired
    private MatchmakingIntroductionService introductionService;

    @Override
    public void execute(JobExecutionContext context) {
        try {
            List<UUID> expired = introductionService.expireIntroductions();
            if (!expired.isEmpty()) {
                log.info("MatchmakingIntroductionExpiryWorker: expired {} introductions", expired.size());
            }
        } catch (Exception e) {
            log.error("MatchmakingIntroductionExpiryWorker failed: {}", e.getMessage(), e);
        }
    }
}
