package com.qaliye.backend.moderation.rekognition;

import java.util.List;

/**
 * Combined moderation decision produced by {@link ImageModerationOrchestrator}.
 */
public record ModerationDecision(
        ImageModerationStatus status,
        boolean faceDetectionRan,
        boolean nudityModerationRan,
        FaceAnalysisResult faceResult,
        NudityAnalysisResult nudityResult,
        List<String> reasons,
        String userMessage
) {
    public static ModerationDecision skipped() {
        return new ModerationDecision(
                ImageModerationStatus.SKIPPED, false, false, null, null, List.of(), null);
    }
}
