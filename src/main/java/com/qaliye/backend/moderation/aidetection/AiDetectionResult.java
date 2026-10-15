package com.qaliye.backend.moderation.aidetection;

/**
 * Immutable result produced by an {@link AiDetectionProvider}.
 *
 * @param aiDetected      {@code true} when AI-generated content was detected
 *                        AND the confidence was at or above the configured threshold.
 * @param confidence      Raw confidence score (0.0–1.0) from the provider.
 * @param topGenerator    Name of the highest-confidence AI generator detected
 *                        (e.g. {@code midjourney}, {@code stable_diffusion}); may be {@code null}.
 * @param verdict         Raw verdict string from the provider ({@code "ai"} or {@code "human"}).
 * @param actionTaken     The action that should be (or was) applied based on config.
 */
public record AiDetectionResult(
        boolean aiDetected,
        double confidence,
        String topGenerator,
        String verdict,
        AiDetectionAction actionTaken
) {}
