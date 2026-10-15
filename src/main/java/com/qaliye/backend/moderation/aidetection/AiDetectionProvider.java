package com.qaliye.backend.moderation.aidetection;

/**
 * Strategy interface for AI-generated image detection providers.
 * <p>
 * Implementations are selected at startup via {@code image.ai-detection.provider}
 * (currently: {@code AI_OR_NOT}).  Future providers (e.g. Hive, SightEngine)
 * only need to implement this interface and be registered in {@link AiDetectionConfig}.
 * </p>
 */
public interface AiDetectionProvider {

    /**
     * Analyses the supplied image bytes and returns an AI detection result.
     *
     * @param imageBytes raw image bytes (JPEG / PNG / WebP etc.)
     * @return non-null result; throws {@link RuntimeException} on provider failure
     */
    AiDetectionResult analyze(byte[] imageBytes);
}
