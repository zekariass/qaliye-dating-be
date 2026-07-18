package com.qaliye.backend.moderation.rekognition;

import java.util.List;

/**
 * Normalized result of a Rekognition DetectModerationLabels call for one profile photo.
 */
public record NudityAnalysisResult(
        boolean passed,
        ImageModerationStatus recommendedStatus,
        boolean nudityDetected,
        boolean sexualContentDetected,
        List<String> triggeredLabels,
        List<String> failureReasons,
        String userMessage
) {}
