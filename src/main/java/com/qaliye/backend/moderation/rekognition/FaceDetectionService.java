package com.qaliye.backend.moderation.rekognition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.rekognition.model.BoundingBox;
import software.amazon.awssdk.services.rekognition.model.FaceDetail;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Analyzes profile photos using Rekognition DetectFaces.
 * <p>
 * Rules applied (when face detection is enabled):
 * <ul>
 *   <li>Reject if zero faces detected.</li>
 *   <li>Select the face with the highest confidence when multiple faces are present.
 *       Multiple faces do NOT cause automatic rejection.</li>
 *   <li>Reject if confidence &lt; {@code minConfidence}.</li>
 *   <li>Reject if brightness &lt; {@code minBrightness}.</li>
 *   <li>Reject if sharpness &lt; {@code minSharpness}.</li>
 *   <li>Reject if face-area percentage &lt; {@code minSizePercent}.</li>
 *   <li>Reject if face is occluded (only when {@code occlusionCheckEnabled=true}).</li>
 * </ul>
 */
@Service
public class FaceDetectionService {

    private static final Logger log = LoggerFactory.getLogger(FaceDetectionService.class);

    private final ImageModerationProperties props;
    private final RekognitionImageClient rekognitionClient;

    public FaceDetectionService(ImageModerationProperties props,
                                RekognitionImageClient rekognitionClient) {
        this.props = props;
        this.rekognitionClient = rekognitionClient;
    }

    public FaceAnalysisResult analyze(byte[] imageBytes) {
        ImageModerationProperties.FaceDetection cfg = props.getFaceDetection();

        List<FaceDetail> faces = rekognitionClient.detectFaces(imageBytes, cfg.isOcclusionCheckEnabled());
        int faceCount = faces.size();

        log.debug("DetectFaces returned {} face(s)", faceCount);

        if (faceCount == 0) {
            return new FaceAnalysisResult(false, 0, null, null, null, null, null,
                    List.of("NO_FACE_DETECTED"),
                    "The profile photo should have a clear and visible face.");
        }

        FaceDetail best = faces.stream()
                .max(Comparator.comparingDouble(f -> f.confidence() != null ? f.confidence() : 0f))
                .orElseThrow();

        double confidence = best.confidence() != null ? best.confidence() : 0.0;
        Double brightness = best.quality() != null && best.quality().brightness() != null
                ? (double) best.quality().brightness() : null;
        Double sharpness  = best.quality() != null && best.quality().sharpness() != null
                ? (double) best.quality().sharpness() : null;
        Double faceArea   = computeFaceAreaPercent(best.boundingBox());
        Boolean occluded  = extractOcclusion(best, cfg.isOcclusionCheckEnabled());

        List<String> failures = new ArrayList<>();
        String userMessage = null;

        if (confidence < cfg.getMinConfidence()) {
            failures.add("FACE_CONFIDENCE_TOO_LOW");
            userMessage = "The profile photo should have a clear and visible face.";
        }
        if (brightness != null && brightness < cfg.getMinBrightness()) {
            failures.add("FACE_BRIGHTNESS_TOO_LOW");
            if (userMessage == null) userMessage = "The photo is too dark. Please upload a brighter photo.";
        }
        if (sharpness != null && sharpness < cfg.getMinSharpness()) {
            failures.add("FACE_SHARPNESS_TOO_LOW");
            if (userMessage == null) userMessage = "The photo is too blurry. Please upload a clearer photo.";
        }
        if (faceArea != null && faceArea < cfg.getMinSizePercent()) {
            failures.add("FACE_TOO_SMALL");
            if (userMessage == null) userMessage = "Your face is too small in this photo. Please upload a closer photo.";
        }
        if (cfg.isOcclusionCheckEnabled() && Boolean.TRUE.equals(occluded)) {
            failures.add("FACE_OCCLUDED");
            if (userMessage == null) userMessage = "Your face appears to be partially covered. Please upload a clearer photo.";
        }

        boolean passed = failures.isEmpty();
        return new FaceAnalysisResult(passed, faceCount, confidence, brightness, sharpness,
                faceArea, occluded, failures, passed ? null : userMessage);
    }

    private Double computeFaceAreaPercent(BoundingBox bb) {
        if (bb == null) return null;
        double w = bb.width()  != null ? bb.width()  : 0.0;
        double h = bb.height() != null ? bb.height() : 0.0;
        return w * h * 100.0;
    }

    private Boolean extractOcclusion(FaceDetail face, boolean enabled) {
        if (!enabled || face.faceOccluded() == null) return null;
        return face.faceOccluded().value();
    }
}
