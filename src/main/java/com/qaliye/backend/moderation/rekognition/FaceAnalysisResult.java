package com.qaliye.backend.moderation.rekognition;

import java.util.List;

/**
 * Normalized result of a Rekognition DetectFaces call for one profile photo.
 */
public record FaceAnalysisResult(
        boolean passed,
        int faceCount,
        Double selectedFaceConfidence,
        Double brightness,
        Double sharpness,
        Double faceAreaPercentage,
        Boolean faceOccluded,
        List<String> failureReasons,
        String userMessage
) {}
