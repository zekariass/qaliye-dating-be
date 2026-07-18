package com.qaliye.backend.moderation;

import com.qaliye.backend.moderation.rekognition.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.rekognition.model.ModerationLabel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NudityModerationServiceTest {

    @Mock RekognitionImageClient rekognitionClient;

    byte[] dummyBytes = new byte[100];

    @Test
    void approves_clean_image() {
        when(rekognitionClient.detectModerationLabels(any(), anyFloat())).thenReturn(List.of());

        NudityAnalysisResult result = service(SuggestiveContentAction.MANUAL_REVIEW).analyze(dummyBytes);

        assertThat(result.passed()).isTrue();
        assertThat(result.recommendedStatus()).isEqualTo(ImageModerationStatus.APPROVED);
        assertThat(result.nudityDetected()).isFalse();
        assertThat(result.sexualContentDetected()).isFalse();
    }

    @Test
    void rejects_explicit_nudity() {
        when(rekognitionClient.detectModerationLabels(any(), anyFloat()))
                .thenReturn(List.of(label("Nudity", "Explicit Nudity", 95f)));

        NudityAnalysisResult result = service(SuggestiveContentAction.MANUAL_REVIEW).analyze(dummyBytes);

        assertThat(result.passed()).isFalse();
        assertThat(result.recommendedStatus()).isEqualTo(ImageModerationStatus.REJECTED);
        assertThat(result.nudityDetected()).isTrue();
        assertThat(result.failureReasons()).contains("EXPLICIT_CONTENT_DETECTED");
    }

    @Test
    void rejects_sexual_activity() {
        when(rekognitionClient.detectModerationLabels(any(), anyFloat()))
                .thenReturn(List.of(label("Sexual Activity", "Explicit Nudity", 92f)));

        NudityAnalysisResult result = service(SuggestiveContentAction.APPROVE).analyze(dummyBytes);

        assertThat(result.passed()).isFalse();
        assertThat(result.recommendedStatus()).isEqualTo(ImageModerationStatus.REJECTED);
        assertThat(result.sexualContentDetected()).isTrue();
    }

    @Test
    void sends_suggestive_to_manual_review_when_action_is_manual_review() {
        when(rekognitionClient.detectModerationLabels(any(), anyFloat()))
                .thenReturn(List.of(label("Partial Nudity", "Suggestive", 91f)));

        NudityAnalysisResult result = service(SuggestiveContentAction.MANUAL_REVIEW).analyze(dummyBytes);

        assertThat(result.passed()).isFalse();
        assertThat(result.recommendedStatus()).isEqualTo(ImageModerationStatus.MANUAL_REVIEW);
        assertThat(result.nudityDetected()).isFalse();
        assertThat(result.failureReasons()).contains("SUGGESTIVE_CONTENT_REVIEW");
    }

    @Test
    void rejects_suggestive_when_action_is_reject() {
        when(rekognitionClient.detectModerationLabels(any(), anyFloat()))
                .thenReturn(List.of(label("Partial Nudity", "Suggestive", 91f)));

        NudityAnalysisResult result = service(SuggestiveContentAction.REJECT).analyze(dummyBytes);

        assertThat(result.passed()).isFalse();
        assertThat(result.recommendedStatus()).isEqualTo(ImageModerationStatus.REJECTED);
        assertThat(result.failureReasons()).contains("SUGGESTIVE_CONTENT_REJECTED");
    }

    @Test
    void approves_suggestive_when_action_is_approve() {
        when(rekognitionClient.detectModerationLabels(any(), anyFloat()))
                .thenReturn(List.of(label("Partial Nudity", "Suggestive", 91f)));

        NudityAnalysisResult result = service(SuggestiveContentAction.APPROVE).analyze(dummyBytes);

        assertThat(result.passed()).isTrue();
        assertThat(result.recommendedStatus()).isEqualTo(ImageModerationStatus.APPROVED);
    }

    @Test
    void ignores_non_nudity_labels_like_violence() {
        when(rekognitionClient.detectModerationLabels(any(), anyFloat()))
                .thenReturn(List.of(label("Graphic Violence", "Violence", 99f)));

        NudityAnalysisResult result = service(SuggestiveContentAction.MANUAL_REVIEW).analyze(dummyBytes);

        assertThat(result.passed()).isTrue();
        assertThat(result.recommendedStatus()).isEqualTo(ImageModerationStatus.APPROVED);
    }

    @Test
    void explicit_beats_suggestive() {
        when(rekognitionClient.detectModerationLabels(any(), anyFloat()))
                .thenReturn(List.of(
                        label("Nudity", "Explicit Nudity", 95f),
                        label("Partial Nudity", "Suggestive", 91f)));

        NudityAnalysisResult result = service(SuggestiveContentAction.MANUAL_REVIEW).analyze(dummyBytes);

        assertThat(result.recommendedStatus()).isEqualTo(ImageModerationStatus.REJECTED);
    }

    // -----------------------------------------------------------------------
    private NudityModerationService service(SuggestiveContentAction action) {
        ImageModerationProperties props = new ImageModerationProperties();
        props.getNudityModeration().setSuggestiveContentAction(action);
        return new NudityModerationService(props, rekognitionClient);
    }

    private ModerationLabel label(String name, String parent, float confidence) {
        return ModerationLabel.builder()
                .name(name)
                .parentName(parent)
                .confidence(confidence)
                .build();
    }
}
