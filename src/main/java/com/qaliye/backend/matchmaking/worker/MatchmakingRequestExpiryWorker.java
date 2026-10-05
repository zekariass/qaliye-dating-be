package com.qaliye.backend.matchmaking.worker;

import com.qaliye.backend.matchmaking.service.MatchmakingRequestService;
import com.qaliye.backend.notifications.NotificationDispatcher;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Quartz sweeper: expires OPEN matchmaking requests that have passed {@code expires_at}
 * and issues escrow refunds for each.
 *
 * <p>Runs every 5 minutes. Mirrors the pattern of
 * {@link com.qaliye.backend.blinddate.worker.BlindDateExpiryWorker}.
 */
@Component
public class MatchmakingRequestExpiryWorker implements Job {

    private static final Logger log = LoggerFactory.getLogger(MatchmakingRequestExpiryWorker.class);

    @Autowired
    private MatchmakingRequestService requestService;

    @Autowired
    private NotificationDispatcher notificationDispatcher;

    @Override
    public void execute(JobExecutionContext context) {
        try {
            List<UUID> expired = requestService.expireOpenRequests();
            if (!expired.isEmpty()) {
                log.info("MatchmakingRequestExpiryWorker: expired {} requests", expired.size());
                for (UUID requestId : expired) {
                    try {
                        var request = requestService.getRequestAdmin(requestId);
                        notificationDispatcher.dispatchMatchmakingRequestExpiredNotification(
                                request.userId(), requestId);
                    } catch (Exception e) {
                        log.error("MatchmakingRequestExpiryWorker: notification failed for {}: {}",
                                requestId, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("MatchmakingRequestExpiryWorker failed: {}", e.getMessage(), e);
        }
    }
}
