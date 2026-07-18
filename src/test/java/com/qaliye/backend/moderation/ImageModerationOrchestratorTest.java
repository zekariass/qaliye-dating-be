package com.qaliye.backend.moderation;

import com.qaliye.backend.moderation.rekognition.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImageModerationOrchestratorTest {

    @Mock FaceDetectionService faceDetectionService;
    @Mock NudityModerationService nudityModerationService;

    byte[] dummyBytes = new byte[100];

    // -----------------------------------------------------------------------
    // Global disabled
    // -----------------------------------------------------------------------

    @Test
    void returns_skipped_when_globally_disabled() {
        ImageModerationProperties props = propsWithFlags(false, true, true);
        ImageModerationOrchestrator orchestrator = new ImageModerationOrchestrator(
                props, faceDetectionService, nudityModerationService);

        ModerationDecision decision = orchestrator.moderate(dummyBytes);

        assertThat(decision.status()).isEqualTo(ImageModerationStatus.SKIPPED);
        verify(faceDetectionService, never()).analyze(any());
        verify(nudityModerationService, never()).analyze(any());
    }

    @Test
    void returns_skipped_when_both_modules_disabled() {
        ImageModerationProperties props = propsWithFlags(true, false, false);
        ImageModerationOrchestrator orchestrator = new ImageModerationOrchestrator(
                props, faceDetectionService, nudityModerationService);

        ModerationDecision decision = orchestrator.moderate(dummyBytes);

        assertThat(decision.status()).isEqualTo(ImageModerationStatus.SKIPPED);
        verify(faceDetectionService, never()).analyze(any());
        verify(nudityModerationService, never()).analyze(any());
    }

    // -----------------------------------------------------------------------
    // Module isolation
    // -----------------------------------------------------------------------

    @Test
    void only_face_detection_runs_when_nudity_disabled() {
        ImageModerationProperties props = propsWithFlags(true, true, false);
        when(faceDetectionService.analyze(any())).thenReturn(passedFace());

        ModerationDecision decision = new ImageModerationOrchestrator(
                props, faceDetectionService, nudityModerationService).moderate(dummyBytes);

        assertThat(decision.faceDetectionRan()).isTrue();
        assertThat(decision.nudityModerationRan()).isFalse();
        verify(nudityModerationService, never()).analyze(any());
    }

    @Test
    void only_nudity_runs_when_face_disabled() {
        ImageModerationProperties props = propsWithFlags(true, false, true);
        when(nudityModerationService.analyze(any())).thenReturn(approvedNudity());

        ModerationDecision decision = new ImageModerationOrchestrator(
                props, faceDetectionService, nudityModerationService).moderate(dummyBytes);

        assertThat(decision.faceDetectionRan()).isFalse();
        assertThat(decision.nudityModerationRan()).isTrue();
        verify(faceDetectionService, never()).analyze(any());
    }

    // -----------------------------------------------------------------------
    // Decision combination
    // -----------------------------------------------------------------------

    @Test
    void approved_when_both_pass() {
        ImageModerationProperties props = propsWithFlags(true, true, true);
        when(faceDetectionService.analyze(any())).thenReturn(passedFace());
        when(nudityModerationService.analyze(any())).thenReturn(approvedNudity());

        ModerationDecision decision = new ImageModerationOrchestrator(
                props, faceDetectionService, nudityModerationService).moderate(dummyBytes);

        assertThat(decision.status()).isEqualTo(ImageModerationStatus.APPROVED);
    }

    @Test
    void rejected_when_face_fails() {
        ImageModerationProperties props = propsWithFlags(true, true, true);
        when(faceDetectionService.analyze(any())).thenReturn(failedFace());
        when(nudityModerationService.analyze(any())).thenReturn(approvedNudity());

        ModerationDecision decision = new ImageModerationOrchestrator(
                props, faceDetectionService, nudityModerationService).moderate(dummyBytes);

        assertThat(decision.status()).isEqualTo(ImageModerationStatus.REJECTED);
        assertThat(decision.reasons()).contains("NO_FACE_DETECTED");
    }

    @Test
    void rejected_when_nudity_detected() {
        ImageModerationProperties props = propsWithFlags(true, true, true);
        when(faceDetectionService.analyze(any())).thenReturn(passedFace());
        when(nudityModerationService.analyze(any())).thenReturn(rejectedNudity());

        ModerationDecision decision = new ImageModerationOrchestrator(
                props, faceDetectionService, nudityModerationService).moderate(dummyBytes);

        assertThat(decision.status()).isEqualTo(ImageModerationStatus.REJECTED);
        assertThat(decision.reasons()).contains("EXPLICIT_CONTENT_DETECTED");
    }

    @Test
    void manual_review_when_nudity_suggestive() {
        ImageModerationProperties props = propsWithFlags(true, true, true);
        when(faceDetectionService.analyze(any())).thenReturn(passedFace());
        when(nudityModerationService.analyze(any())).thenReturn(manualReviewNudity());

        ModerationDecision decision = new ImageModerationOrchestrator(
                props, faceDetectionService, nudityModerationService).moderate(dummyBytes);

        assertThat(decision.status()).isEqualTo(ImageModerationStatus.MANUAL_REVIEW);
    }

    @Test
    void rejected_takes_precedence_over_manual_review() {
        ImageModerationProperties props = propsWithFlags(true, true, true);
        when(faceDetectionService.analyze(any())).thenReturn(failedFace());
        when(nudityModerationService.analyze(any())).thenReturn(manualReviewNudity());

        ModerationDecision decision = new ImageModerationOrchestrator(
                props, faceDetectionService, nudityModerationService).moderate(dummyBytes);

        assertThat(decision.status()).isEqualTo(ImageModerationStatus.REJECTED);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ImageModerationProperties propsWithFlags(boolean global, boolean face, boolean nudity) {
        ImageModerationProperties p = new ImageModerationProperties();
        p.setEnabled(global);
        p.getFaceDetection().setEnabled(face);
        p.getNudityModeration().setEnabled(nudity);
        return p;
    }

    private FaceAnalysisResult passedFace() {
        return new FaceAnalysisResult(true, 1, 98.0, 80.0, 75.0, 30.0, false, List.of(), null);
    }

    private FaceAnalysisResult failedFace() {
        return new FaceAnalysisResult(false, 0, null, null, null, null, null,
                List.of("NO_FACE_DETECTED"), "The profile photo should have a clear and visible face.");
    }

    private NudityAnalysisResult approvedNudity() {
        return new NudityAnalysisResult(true, ImageModerationStatus.APPROVED,
                false, false, List.of(), List.of(), null);
    }

    private NudityAnalysisResult rejectedNudity() {
        return new NudityAnalysisResult(false, ImageModerationStatus.REJECTED,
                true, false, List.of("Nudity"), List.of("EXPLICIT_CONTENT_DETECTED"),
                "This photo could not be approved.");
    }

    private NudityAnalysisResult manualReviewNudity() {
        return new NudityAnalysisResult(false, ImageModerationStatus.MANUAL_REVIEW,
                false, false, List.of("Partial Nudity"), List.of("SUGGESTIVE_CONTENT_REVIEW"),
                "This photo requires additional review.");
    }
}
