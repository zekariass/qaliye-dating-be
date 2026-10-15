package com.qaliye.backend.moderation.aidetection;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration for the AI-generated image detection pipeline.
 * <p>
 * Bound from the {@code image.ai-detection.*} namespace; all values are
 * overridable via environment variables (see {@code application.yml}).
 * </p>
 */
@ConfigurationProperties(prefix = "image.ai-detection")
@Validated
public class AiDetectionProperties {

    /** Master on/off switch. Defaults to {@code false} (opt-in). */
    private boolean enabled = false;

    /**
     * Which provider to use. Valid values: {@code AI_OR_NOT}.
     * Future providers can be added to {@link AiDetectionConfig} without
     * touching this class.
     */
    @NotNull
    private String provider = "AI_OR_NOT";

    /**
     * Minimum AI-confidence score (0.0–1.0) required to treat an image as
     * AI-generated.  Detections below this threshold are ignored.
     */
    @DecimalMin("0.0") @DecimalMax("1.0")
    private double confidenceThreshold = 0.85;

    /**
     * What to do when AI-generated content is detected above the threshold.
     * Defaults to {@link AiDetectionAction#ADVISE_ONLY} (safe default: no enforcement).
     */
    @NotNull
    private AiDetectionAction action = AiDetectionAction.ADVISE_ONLY;

    @Valid
    @NotNull
    private AiOrNot aiOrNot = new AiOrNot();

    // -----------------------------------------------------------------------
    // Getters / setters
    // -----------------------------------------------------------------------

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public double getConfidenceThreshold() { return confidenceThreshold; }
    public void setConfidenceThreshold(double confidenceThreshold) { this.confidenceThreshold = confidenceThreshold; }

    public AiDetectionAction getAction() { return action; }
    public void setAction(AiDetectionAction action) { this.action = action; }

    public AiOrNot getAiOrNot() { return aiOrNot; }
    public void setAiOrNot(AiOrNot aiOrNot) { this.aiOrNot = aiOrNot; }

    // -----------------------------------------------------------------------
    // Nested: AI or Not provider config
    // -----------------------------------------------------------------------
    public static class AiOrNot {

        private String apiKey = "";
        private String baseUrl = "https://api.aiornot.com/v2/image/sync";

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }
}
