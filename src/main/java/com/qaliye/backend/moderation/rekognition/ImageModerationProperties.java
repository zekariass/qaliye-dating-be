package com.qaliye.backend.moderation.rekognition;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration for the image-moderation pipeline.
 * <p>
 * Bound from the {@code image.moderation.*} namespace. Set via environment
 * variables (e.g. {@code IMAGE_MODERATION_ENABLED=true}).  All threshold
 * values are intentional defaults; tune them using real Qaliye profile images
 * before setting production values.
 * </p>
 */
@ConfigurationProperties(prefix = "image.moderation")
@Validated
public class ImageModerationProperties {

    private boolean enabled = true;

    @Min(0)
    private int maxRetries = 3;

    @Min(0)
    private long retryInitialDelayMs = 1000L;

    @Valid
    @NotNull
    private FaceDetection faceDetection = new FaceDetection();

    @Valid
    @NotNull
    private NudityModeration nudityModeration = new NudityModeration();

    @Valid
    @NotNull
    private Conversion conversion = new Conversion();

    // -----------------------------------------------------------------------
    // Config-version fingerprint used for duplicate-processing prevention.
    // If thresholds or enabled flags change, existing images are eligible for
    // reprocessing because the config version changes.
    // -----------------------------------------------------------------------
    public String configVersion() {
        return String.format("fd:%b:%.1f:%.1f:%.1f:%.1f:%b|nm:%b:%.1f:%s|cv:%d:%d:%d:%d:%.2f",
                faceDetection.enabled,
                faceDetection.minConfidence,
                faceDetection.minBrightness,
                faceDetection.minSharpness,
                faceDetection.minSizePercent,
                faceDetection.occlusionCheckEnabled,
                nudityModeration.enabled,
                nudityModeration.minConfidence,
                nudityModeration.suggestiveContentAction,
                conversion.maxFileSizeBytes,
                conversion.maxWidth,
                conversion.maxHeight,
                conversion.maxPixels,
                conversion.jpegQuality);
    }

    // -----------------------------------------------------------------------
    // Getters / setters
    // -----------------------------------------------------------------------

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public long getRetryInitialDelayMs() { return retryInitialDelayMs; }
    public void setRetryInitialDelayMs(long retryInitialDelayMs) { this.retryInitialDelayMs = retryInitialDelayMs; }

    public FaceDetection getFaceDetection() { return faceDetection; }
    public void setFaceDetection(FaceDetection faceDetection) { this.faceDetection = faceDetection; }

    public NudityModeration getNudityModeration() { return nudityModeration; }
    public void setNudityModeration(NudityModeration nudityModeration) { this.nudityModeration = nudityModeration; }

    public Conversion getConversion() { return conversion; }
    public void setConversion(Conversion conversion) { this.conversion = conversion; }

    // -----------------------------------------------------------------------
    // Nested: face-detection thresholds
    // -----------------------------------------------------------------------
    public static class FaceDetection {

        private boolean enabled = true;

        /**
         * Minimum Rekognition face confidence (0–100) for the image to pass.
         * Default: 95. Tune using actual Qaliye profile images.
         */
        @DecimalMin("0") @DecimalMax("100")
        private double minConfidence = 95.0;

        /**
         * Minimum face-region brightness (0–100).
         * Default: 25. Too-dark images fail this check.
         */
        @DecimalMin("0") @DecimalMax("100")
        private double minBrightness = 25.0;

        /**
         * Minimum face-region sharpness (0–100).
         * Default: 40. Blurry images fail this check.
         */
        @DecimalMin("0") @DecimalMax("100")
        private double minSharpness = 40.0;

        /**
         * Minimum face area as a percentage of the full image (0–100).
         * Default: 20. Images where the face is too far away fail this check.
         */
        @DecimalMin("0") @DecimalMax("100")
        private double minSizePercent = 20.0;

        /**
         * Whether to evaluate the FaceOccluded attribute returned by Rekognition.
         * Disabled by default because the attribute is unreliable at low-confidence
         * thresholds and can produce false positives.
         */
        private boolean occlusionCheckEnabled = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public double getMinConfidence() { return minConfidence; }
        public void setMinConfidence(double minConfidence) { this.minConfidence = minConfidence; }

        public double getMinBrightness() { return minBrightness; }
        public void setMinBrightness(double minBrightness) { this.minBrightness = minBrightness; }

        public double getMinSharpness() { return minSharpness; }
        public void setMinSharpness(double minSharpness) { this.minSharpness = minSharpness; }

        public double getMinSizePercent() { return minSizePercent; }
        public void setMinSizePercent(double minSizePercent) { this.minSizePercent = minSizePercent; }

        public boolean isOcclusionCheckEnabled() { return occlusionCheckEnabled; }
        public void setOcclusionCheckEnabled(boolean occlusionCheckEnabled) { this.occlusionCheckEnabled = occlusionCheckEnabled; }
    }

    // -----------------------------------------------------------------------
    // Nested: nudity-moderation thresholds
    // -----------------------------------------------------------------------
    public static class NudityModeration {

        private boolean enabled = true;

        /**
         * Minimum Rekognition confidence (0–100) for a nudity label to be acted on.
         * Default: 90. Labels below this threshold are ignored.
         */
        @DecimalMin("0") @DecimalMax("100")
        private double minConfidence = 90.0;

        /**
         * Action to take when suggestive (non-explicit) content is detected.
         * Default: MANUAL_REVIEW.
         */
        @NotNull
        private SuggestiveContentAction suggestiveContentAction = SuggestiveContentAction.MANUAL_REVIEW;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public double getMinConfidence() { return minConfidence; }
        public void setMinConfidence(double minConfidence) { this.minConfidence = minConfidence; }

        public SuggestiveContentAction getSuggestiveContentAction() { return suggestiveContentAction; }
        public void setSuggestiveContentAction(SuggestiveContentAction action) { this.suggestiveContentAction = action; }
    }

    // -----------------------------------------------------------------------
    // Nested: WebP conversion limits
    // -----------------------------------------------------------------------
    public static class Conversion {

        @Min(0)
        private int maxFileSizeBytes = 8 * 1024 * 1024;

        @Min(0)
        private int maxWidth = 4096;

        @Min(0)
        private int maxHeight = 4096;

        @Min(0)
        private int maxPixels = 25_000_000;

        @DecimalMin("0.1") @DecimalMax("1.0")
        private double jpegQuality = 0.85;

        public int getMaxFileSizeBytes() { return maxFileSizeBytes; }
        public void setMaxFileSizeBytes(int maxFileSizeBytes) { this.maxFileSizeBytes = maxFileSizeBytes; }

        public int getMaxWidth() { return maxWidth; }
        public void setMaxWidth(int maxWidth) { this.maxWidth = maxWidth; }

        public int getMaxHeight() { return maxHeight; }
        public void setMaxHeight(int maxHeight) { this.maxHeight = maxHeight; }

        public int getMaxPixels() { return maxPixels; }
        public void setMaxPixels(int maxPixels) { this.maxPixels = maxPixels; }

        public float getJpegQuality() { return (float) jpegQuality; }
        public void setJpegQuality(double jpegQuality) { this.jpegQuality = jpegQuality; }
    }
}
