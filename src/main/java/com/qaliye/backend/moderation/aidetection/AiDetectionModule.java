package com.qaliye.backend.moderation.aidetection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Moderation module that gates and delegates AI-generated image detection.
 * <p>
 * Only photos with {@code photo_order <= 1} (the primary photo and the first
 * card photo) are checked.  All other photos are skipped regardless of config.
 * </p>
 */
@Service
public class AiDetectionModule {

    private static final Logger log = LoggerFactory.getLogger(AiDetectionModule.class);

    public static final int MAX_ELIGIBLE_ORDER = 1;

    private final AiDetectionProperties props;
    private final AiDetectionProvider provider;

    public AiDetectionModule(AiDetectionProperties props, AiDetectionProvider provider) {
        this.props    = props;
        this.provider = provider;
    }

    /**
     * Runs AI detection if both the feature is enabled and the photo is eligible.
     *
     * @param imageBytes raw image bytes
     * @param photoOrder the {@code photo_order} of this photo (0-based)
     * @return result when detection ran; {@code null} when skipped
     */
    public AiDetectionResult analyze(byte[] imageBytes, int photoOrder) {
        if (!props.isEnabled()) {
            log.debug("AI detection disabled — skipping");
            return null;
        }
        if (photoOrder > MAX_ELIGIBLE_ORDER) {
            log.debug("Photo order {} > {} — skipping AI detection", photoOrder, MAX_ELIGIBLE_ORDER);
            return null;
        }
        log.debug("Running AI detection for photo_order={}", photoOrder);
        return provider.analyze(imageBytes);
    }
}
