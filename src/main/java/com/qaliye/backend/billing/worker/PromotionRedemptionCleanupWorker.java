package com.qaliye.backend.billing.worker;

import com.qaliye.backend.billing.repository.PromotionRepository;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
public class PromotionRedemptionCleanupWorker implements Job {

    private static final Logger log = LoggerFactory.getLogger(PromotionRedemptionCleanupWorker.class);
    private static final int STALE_RESERVATION_HOURS = 48;

    @Autowired
    private PromotionRepository promotionRepo;

    @Override
    public void execute(JobExecutionContext context) {
        try {
            Instant cutoff = Instant.now().minus(STALE_RESERVATION_HOURS, ChronoUnit.HOURS);
            int expired = promotionRepo.expireStaleRedemptionsOlderThan(cutoff);
            if (expired > 0) {
                log.info("PromotionRedemptionCleanup: expired {} stale reservations older than {}h",
                        expired, STALE_RESERVATION_HOURS);
            } else {
                log.debug("PromotionRedemptionCleanup: no stale reservations found");
            }
        } catch (Exception e) {
            log.error("PromotionRedemptionCleanup error: {}", e.getMessage(), e);
        }
    }
}
