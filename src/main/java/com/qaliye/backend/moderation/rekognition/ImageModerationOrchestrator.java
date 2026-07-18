package com.qaliye.backend.moderation.rekognition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates enabled moderation modules and combines their results into a
 * single {@link ModerationDecision}.
 * <p>
 * Feature-flag behaviour:
 * <ul>
 *   <li>Global disabled → {@link ModerationDecision#skipped()}.</li>
 *   <li>Both modules disabled → {@link ModerationDecision#skipped()}.</li>
 *   <li>Face only → calls DetectFaces; no DetectModerationLabels call.</li>
 *   <li>Nudity only → calls DetectModerationLabels; no DetectFaces call.</li>
 *   <li>Both enabled → calls both (sequentially; see note).</li>
 * </ul>
 *
 * <p>Note: Calls are sequential rather than parallel to keep transaction
 * boundaries simple and avoid complicating retries.  If throughput becomes a
 * concern the two calls can be extracted to {@code CompletableFuture} later.</p>
 */
@Service
public class ImageModerationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ImageModerationOrchestrator.class);

    private final ImageModerationProperties props;
    private final FaceDetectionService faceDetectionService;
    private final NudityModerationService nudityModerationService;

    public ImageModerationOrchestrator(ImageModerationProperties props,
                                       FaceDetectionService faceDetectionService,
                                       NudityModerationService nudityModerationService) {
        this.props = props;
        this.faceDetectionService = faceDetectionService;
        this.nudityModerationService = nudityModerationService;
    }

    /**
     * Runs the configured moderation modules and returns a combined decision.
     * Treats the photo as a primary photo (face detection required).
     *
     * @param imageBytes raw image bytes already downloaded from Supabase Storage
     */
    public ModerationDecision moderate(byte[] imageBytes) {
        return moderate(imageBytes, true);
    }

    /**
     * Runs the configured moderation modules and returns a combined decision.
     *
     * @param imageBytes     raw image bytes already downloaded from Supabase Storage
     * @param isPrimaryPhoto when {@code true}, face detection failures cause REJECTED;
     *                       when {@code false}, face detection still runs for quality metrics
     *                       but failures do not cause rejection — only nudity is enforced
     * @return non-null decision; never throws — exceptions from Rekognition
     *         propagate to the caller, which is responsible for retry handling
     */
    public ModerationDecision moderate(byte[] imageBytes, boolean isPrimaryPhoto) {
        if (!props.isEnabled()) {
            log.debug("Image moderation globally disabled — skipping");
            return ModerationDecision.skipped();
        }

        // Face detection always runs when enabled — for primary photos it enforces
        // rejection on failure; for secondary photos it records metrics only.
        boolean runFace   = props.getFaceDetection().isEnabled();
        boolean runNudity = props.getNudityModeration().isEnabled();

        if (!runFace && !runNudity) {
            log.debug("All moderation modules disabled — skipping");
            return ModerationDecision.skipped();
        }

        FaceAnalysisResult   faceResult   = null;
        NudityAnalysisResult nudityResult = null;

        if (runFace) {
            log.debug("Running face-detection module (isPrimary={})", isPrimaryPhoto);
            faceResult = faceDetectionService.analyze(imageBytes);
        }
        if (runNudity) {
            log.debug("Running nudity-moderation module");
            nudityResult = nudityModerationService.analyze(imageBytes);
        }

        return combine(runFace, runNudity, isPrimaryPhoto, faceResult, nudityResult);
    }

    // -----------------------------------------------------------------------
    // Decision combination
    // -----------------------------------------------------------------------

    private ModerationDecision combine(boolean faceRan, boolean nudityRan, boolean isPrimary,
                                       FaceAnalysisResult face, NudityAnalysisResult nudity) {
        List<String> reasons = new ArrayList<>();
        String userMessage   = null;
        ImageModerationStatus status = ImageModerationStatus.APPROVED;

        if (faceRan && face != null && !face.passed() && isPrimary) {
            status      = ImageModerationStatus.REJECTED;
            userMessage = face.userMessage();
            reasons.addAll(face.failureReasons());
        }

        if (nudityRan && nudity != null) {
            reasons.addAll(nudity.failureReasons());
            switch (nudity.recommendedStatus()) {
                case REJECTED -> {
                    status = ImageModerationStatus.REJECTED;
                    if (userMessage == null) userMessage = nudity.userMessage();
                }
                case MANUAL_REVIEW -> {
                    if (status == ImageModerationStatus.APPROVED) {
                        status = ImageModerationStatus.MANUAL_REVIEW;
                        if (userMessage == null) userMessage = nudity.userMessage();
                    }
                }
                default -> {}
            }
        }

        log.debug("Moderation decision: {} isPrimary={} reasons={}", status, isPrimary, reasons);
        return new ModerationDecision(status, faceRan, nudityRan, face, nudity, reasons, userMessage);
    }
}
