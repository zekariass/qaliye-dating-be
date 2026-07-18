package com.qaliye.backend.moderation;

import com.qaliye.backend.moderation.rekognition.ImageModerationProperties;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Quartz job that picks up two categories of photos that need moderation:
 *
 * <ol>
 *   <li><b>Error retries</b>: photos with a previous ERROR result whose
 *       {@code retry_after} timestamp has elapsed and whose
 *       {@code attempt_count} is below the configured maximum.</li>
 *   <li><b>Stalled pending</b>: PENDING photos created more than 5 minutes
 *       ago that never received a moderation result (e.g. the Supabase webhook
 *       failed to fire).</li>
 * </ol>
 *
 * Runs every 5 minutes via Quartz.  Each invocation processes at most
 * {@code BATCH_SIZE} photos of each type to bound execution time.
 */
@Component
public class ModerationRetryWorker implements Job {

    private static final Logger log = LoggerFactory.getLogger(ModerationRetryWorker.class);
    private static final int BATCH_SIZE = 10;

    @Autowired
    private ImageModerationResultService moderationResultService;

    @Autowired
    private PhotoModerationService photoModerationService;

    @Autowired
    private ImageModerationProperties imageModerationProperties;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        if (!imageModerationProperties.isEnabled()) {
            return;
        }

        try {
            retryErrors();
            recoverStalled();
        } catch (Exception e) {
            log.error("ModerationRetryWorker failed: {}", e.getMessage());
            throw new JobExecutionException(e, false);
        }
    }

    private void retryErrors() {
        int maxRetries = imageModerationProperties.getMaxRetries();
        List<Map<String, Object>> rows = moderationResultService.findRetryEligible(BATCH_SIZE);

        for (Map<String, Object> row : rows) {
            UUID    imageId    = (UUID) row.get("image_id");
            UUID    profileId  = (UUID) row.get("profile_id");
            String  storagePath = (String) row.get("image_storage_path");
            int     attempts   = ((Number) row.get("attempt_count")).intValue();
            boolean isPrimary  = Boolean.TRUE.equals(row.get("is_primary"));

            if (attempts >= maxRetries) {
                log.debug("Photo {} exhausted {} retries — leaving as ERROR", imageId, maxRetries);
                continue;
            }

            log.info("Retrying moderation for photo={} attempt={}", imageId, attempts + 1);
            photoModerationService.processPhotoModeration(imageId, profileId, storagePath, "PENDING", isPrimary);
        }
    }

    private void recoverStalled() {
        List<Map<String, Object>> rows = moderationResultService.findStalledPending(BATCH_SIZE);

        for (Map<String, Object> row : rows) {
            UUID    imageId    = (UUID) row.get("image_id");
            UUID    profileId  = (UUID) row.get("profile_id");
            String  storagePath = (String) row.get("full_storage_path");
            boolean isPrimary  = Boolean.TRUE.equals(row.get("is_primary"));

            log.info("Recovering stalled photo={}", imageId);
            photoModerationService.processPhotoModeration(imageId, profileId, storagePath, "PENDING", isPrimary);
        }
    }
}
