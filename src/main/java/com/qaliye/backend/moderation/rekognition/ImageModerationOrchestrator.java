package com.qaliye.backend.moderation.rekognition;

import com.qaliye.backend.moderation.aidetection.AiDetectionModule;
import com.qaliye.backend.moderation.aidetection.AiDetectionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
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

    @Autowired(required = false)
    @Nullable
    private AiDetectionModule aiDetectionModule;

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
        return moderate(imageBytes, true, 0);
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
        return moderate(imageBytes, isPrimaryPhoto, isPrimaryPhoto ? 0 : Integer.MAX_VALUE);
    }

    /**
     * Runs the configured moderation modules and returns a combined decision.
     * Uses {@code imageBytes} for all modules including AI detection.
     *
     * @param imageBytes     raw image bytes already downloaded from Supabase Storage
     * @param isPrimaryPhoto when {@code true}, face detection failures cause REJECTED
     * @param photoOrder     zero-based photo order; only orders 0 and 1 are eligible for AI detection
     */
    public ModerationDecision moderate(byte[] imageBytes, boolean isPrimaryPhoto, int photoOrder) {
        return moderate(imageBytes, imageBytes, isPrimaryPhoto, photoOrder);
    }

    /**
     * Runs the configured moderation modules and returns a combined decision.
     *
     * @param imageBytes       image bytes for Rekognition modules (must be JPEG/PNG —
     *                         the converted output of {@code prepareForModeration})
     * @param aiDetectionBytes image bytes for AI detection — pass the original
     *                         unconverted file (e.g. WebP) because re-encoding
     *                         degrades AI-detection accuracy
     * @param isPrimaryPhoto   when {@code true}, face detection failures cause REJECTED
     * @param photoOrder       zero-based photo order; only orders 0 and 1 are eligible for AI detection
     */
    public ModerationDecision moderate(byte[] imageBytes, byte[] aiDetectionBytes,
                                       boolean isPrimaryPhoto, int photoOrder) {
        if (!props.isEnabled()) {
            log.debug("Image moderation globally disabled — skipping");
            return ModerationDecision.skipped();
        }

        boolean runFace   = props.getFaceDetection().isEnabled();
        boolean runNudity = props.getNudityModeration().isEnabled();

        if (!runFace && !runNudity && aiDetectionModule == null) {
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

        AiDetectionResult aiResult = null;
        if (aiDetectionModule != null) {
            try {
                aiResult = aiDetectionModule.analyze(aiDetectionBytes, photoOrder);
            } catch (Exception e) {
                log.warn("AI detection failed — skipping: {}", e.getMessage());
            }
        }

        return combine(runFace, runNudity, isPrimaryPhoto, faceResult, nudityResult, aiResult);
    }

    // -----------------------------------------------------------------------
    // Decision combination
    // -----------------------------------------------------------------------

    private ModerationDecision combine(boolean faceRan, boolean nudityRan, boolean isPrimary,
                                       FaceAnalysisResult face, NudityAnalysisResult nudity,
                                       AiDetectionResult aiResult) {
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

        if (aiResult != null && aiResult.aiDetected()) {
            switch (aiResult.actionTaken()) {
                case REJECTED -> {
                    status = ImageModerationStatus.REJECTED;
                    reasons.add("AI_GENERATED_CONTENT");
                    if (userMessage == null) userMessage = "This photo appears to be AI-generated. Please upload a real photo.";
                }
                case MANUAL_REVIEW -> {
                    if (status == ImageModerationStatus.APPROVED) {
                        status = ImageModerationStatus.MANUAL_REVIEW;
                        reasons.add("AI_GENERATED_CONTENT");
                        if (userMessage == null) userMessage = "This photo has been flagged for review.";
                    }
                }
                case ADVISE_ONLY -> reasons.add("AI_GENERATED_ADVISORY");
            }
        }

        log.debug("Moderation decision: {} isPrimary={} reasons={}", status, isPrimary, reasons);
        return new ModerationDecision(status, faceRan, nudityRan, face, nudity, reasons, userMessage, aiResult);
    }
}
