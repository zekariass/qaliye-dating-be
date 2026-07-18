package com.qaliye.backend.moderation;

import com.qaliye.backend.moderation.rekognition.FaceAnalysisResult;
import com.qaliye.backend.moderation.rekognition.FaceDetectionService;
import com.qaliye.backend.moderation.rekognition.ImageModerationProperties;
import com.qaliye.backend.moderation.rekognition.RekognitionImageClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.rekognition.model.BoundingBox;
import software.amazon.awssdk.services.rekognition.model.FaceDetail;
import software.amazon.awssdk.services.rekognition.model.ImageQuality;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FaceDetectionServiceTest {

    @Mock RekognitionImageClient rekognitionClient;

    FaceDetectionService service;
    byte[] dummyBytes = new byte[100];

    @BeforeEach
    void setUp() {
        ImageModerationProperties props = new ImageModerationProperties();
        service = new FaceDetectionService(props, rekognitionClient);
    }

    @Test
    void passes_when_face_meets_all_thresholds() {
        when(rekognitionClient.detectFaces(any(), anyBoolean()))
                .thenReturn(List.of(face(98f, 80f, 75f, 0.7f, 0.7f)));

        FaceAnalysisResult result = service.analyze(dummyBytes);

        assertThat(result.passed()).isTrue();
        assertThat(result.faceCount()).isEqualTo(1);
        assertThat(result.failureReasons()).isEmpty();
        assertThat(result.userMessage()).isNull();
    }

    @Test
    void fails_when_no_face_detected() {
        when(rekognitionClient.detectFaces(any(), anyBoolean())).thenReturn(List.of());

        FaceAnalysisResult result = service.analyze(dummyBytes);

        assertThat(result.passed()).isFalse();
        assertThat(result.faceCount()).isEqualTo(0);
        assertThat(result.failureReasons()).contains("NO_FACE_DETECTED");
        assertThat(result.userMessage()).isNotBlank();
    }

    @Test
    void fails_when_confidence_below_threshold() {
        when(rekognitionClient.detectFaces(any(), anyBoolean()))
                .thenReturn(List.of(face(70f, 80f, 75f, 0.5f, 0.5f)));

        FaceAnalysisResult result = service.analyze(dummyBytes);

        assertThat(result.passed()).isFalse();
        assertThat(result.failureReasons()).contains("FACE_CONFIDENCE_TOO_LOW");
    }

    @Test
    void fails_when_brightness_below_threshold() {
        when(rekognitionClient.detectFaces(any(), anyBoolean()))
                .thenReturn(List.of(face(98f, 10f, 75f, 0.5f, 0.5f)));

        FaceAnalysisResult result = service.analyze(dummyBytes);

        assertThat(result.passed()).isFalse();
        assertThat(result.failureReasons()).contains("FACE_BRIGHTNESS_TOO_LOW");
    }

    @Test
    void fails_when_sharpness_below_threshold() {
        when(rekognitionClient.detectFaces(any(), anyBoolean()))
                .thenReturn(List.of(face(98f, 80f, 10f, 0.5f, 0.5f)));

        FaceAnalysisResult result = service.analyze(dummyBytes);

        assertThat(result.passed()).isFalse();
        assertThat(result.failureReasons()).contains("FACE_SHARPNESS_TOO_LOW");
    }

    @Test
    void fails_when_face_too_small() {
        // Face occupying 0.1*0.1 = 1% of image (below 20% default threshold)
        when(rekognitionClient.detectFaces(any(), anyBoolean()))
                .thenReturn(List.of(face(98f, 80f, 75f, 0.1f, 0.1f)));

        FaceAnalysisResult result = service.analyze(dummyBytes);

        assertThat(result.passed()).isFalse();
        assertThat(result.failureReasons()).contains("FACE_TOO_SMALL");
    }

    @Test
    void picks_highest_confidence_face_when_multiple() {
        FaceDetail low  = face(60f, 80f, 75f, 0.5f, 0.5f);
        FaceDetail high = face(98f, 80f, 75f, 0.7f, 0.7f);
        when(rekognitionClient.detectFaces(any(), anyBoolean())).thenReturn(List.of(low, high));

        FaceAnalysisResult result = service.analyze(dummyBytes);

        assertThat(result.passed()).isTrue();
        assertThat(result.faceCount()).isEqualTo(2);
        assertThat(result.selectedFaceConfidence()).isEqualTo(98.0);
    }

    @Test
    void accumulates_multiple_failure_reasons() {
        // Low confidence + low brightness + low sharpness
        when(rekognitionClient.detectFaces(any(), anyBoolean()))
                .thenReturn(List.of(face(60f, 10f, 5f, 0.5f, 0.5f)));

        FaceAnalysisResult result = service.analyze(dummyBytes);

        assertThat(result.passed()).isFalse();
        assertThat(result.failureReasons()).containsExactlyInAnyOrder(
                "FACE_CONFIDENCE_TOO_LOW", "FACE_BRIGHTNESS_TOO_LOW", "FACE_SHARPNESS_TOO_LOW");
    }

    // -----------------------------------------------------------------------
    private FaceDetail face(float confidence, float brightness, float sharpness,
                             float bbWidth, float bbHeight) {
        return FaceDetail.builder()
                .confidence(confidence)
                .quality(ImageQuality.builder()
                        .brightness(brightness)
                        .sharpness(sharpness)
                        .build())
                .boundingBox(BoundingBox.builder()
                        .width(bbWidth)
                        .height(bbHeight)
                        .left(0.1f)
                        .top(0.1f)
                        .build())
                .build();
    }
}
