package com.qaliye.backend.moderation;

import com.qaliye.backend.moderation.aidetection.AiDetectionAction;
import com.qaliye.backend.moderation.aidetection.AiDetectionModule;
import com.qaliye.backend.moderation.aidetection.AiDetectionResult;
import com.qaliye.backend.moderation.rekognition.ImageModerationOrchestrator;
import com.qaliye.backend.moderation.rekognition.ImageModerationStatus;
import com.qaliye.backend.moderation.rekognition.ModerationDecision;
import com.qaliye.backend.moderation.rekognition.RekognitionProviderException;
import com.qaliye.backend.onboarding.OnboardingService;
import com.qaliye.backend.storage.SupabaseStorageService;
import com.qaliye.backend.user.UserStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PhotoModerationServiceTest {

    @Mock SupabaseStorageService storageService;
    @Mock OnboardingService onboardingService;
    @Mock UserStatusService userStatusService;
    @Mock CacheManager cacheManager;
    @Mock NamedParameterJdbcTemplate jdbc;
    @Mock ImageModerationOrchestrator orchestrator;
    @Mock ImageModerationResultService moderationResultService;
    @Mock ModerationImageConverter moderationImageConverter;
    @Mock AiDetectionModule aiDetectionModule;

    PhotoModerationService service;

    UUID photoId = UUID.randomUUID();
    UUID userId  = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(moderationImageConverter.prepareForModeration(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service = new PhotoModerationService(storageService, onboardingService, userStatusService,
                cacheManager, jdbc, orchestrator, moderationResultService, moderationImageConverter);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void givenClaimed()    { when(moderationResultService.createAndClaim(any(), any(), anyString())).thenReturn(true); }
    private void givenBytes()      { when(storageService.downloadPhoto(anyString())).thenReturn(new byte[]{1, 2, 3}); }
    private void givenRows(int n)  { when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(n); }

    // ── existing tests (updated to pass isPrimary) ────────────────────────────

    @Test
    void skips_when_status_not_pending() {
        service.processPhotoModeration(photoId, userId, "profile-photos/u/p.jpg", "APPROVED", true, 0);

        verify(moderationResultService, never()).createAndClaim(any(), any(), any());
        verify(orchestrator, never()).moderate(any(), any(), anyBoolean(), anyInt());
    }

    @Test
    void skips_when_claim_fails() {
        when(moderationResultService.createAndClaim(any(), any(), anyString())).thenReturn(false);

        service.processPhotoModeration(photoId, userId, "profile-photos/u/p.jpg", "PENDING", true, 0);

        verify(orchestrator, never()).moderate(any(), any(), anyBoolean(), anyInt());
    }

    @Test
    void marks_error_when_image_download_fails() {
        givenClaimed();
        givenRows(1);
        when(storageService.downloadPhoto("profile-photos/u/p.jpg")).thenReturn(null);

        service.processPhotoModeration(photoId, userId, "profile-photos/u/p.jpg", "PENDING", true, 0);

        verify(moderationResultService).markError(eq(photoId), eq("STORAGE_DOWNLOAD_FAILED"),
                eq("Image could not be downloaded from Supabase Storage"),
                eq(List.of("STORAGE_DOWNLOAD_FAILED")),
                eq("Image could not be downloaded from Supabase Storage"));
        verify(orchestrator, never()).moderate(any(), any(), anyBoolean(), anyInt());
    }

    @Test
    void updates_photo_to_approved_and_recomputes_score() {
        givenClaimed(); givenBytes(); givenRows(1);
        when(orchestrator.moderate(any(), any(), eq(true), anyInt()))
                .thenReturn(new ModerationDecision(ImageModerationStatus.APPROVED,
                        true, true, null, null, List.of(), null, null));

        service.processPhotoModeration(photoId, userId, "profile-photos/u/p.jpg", "PENDING", true, 0);

        verify(moderationResultService).markCompleted(eq(photoId), any(), anyString());
        verify(onboardingService).recomputeScore(userId);
        verify(storageService, never()).deleteObject(any(), any());
    }

    @Test
    void updates_photo_to_rejected_and_deletes_from_storage() {
        givenClaimed(); givenBytes(); givenRows(1);
        when(orchestrator.moderate(any(), any(), eq(true), anyInt()))
                .thenReturn(new ModerationDecision(ImageModerationStatus.REJECTED,
                        true, true, null, null,
                        List.of("NO_FACE_DETECTED"), "The profile photo should have a clear and visible face.", null));

        service.processPhotoModeration(photoId, userId, "profile-photos/u/p.jpg", "PENDING", true, 0);

        verify(moderationResultService).markCompleted(eq(photoId), any(), anyString());
        verify(onboardingService).recomputeScore(userId);
        // Storage must be deleted after rejection
        verify(storageService).deleteObject(eq("profile-photos"), eq("u/p.jpg"));
    }

    @Test
    void does_not_delete_storage_on_retryable_error() {
        givenClaimed(); givenBytes();
        givenRows(1);
        when(orchestrator.moderate(any(), any(), anyBoolean(), anyInt()))
                .thenThrow(new RekognitionProviderException("ThrottlingException", "rate limit", true));

        service.processPhotoModeration(photoId, userId, "profile-photos/u/p.jpg", "PENDING", true, 0);

        verify(moderationResultService).markError(eq(photoId), eq("ThrottlingException"), eq("rate limit"),
                eq(List.of("ThrottlingException")), eq("rate limit"));
        verify(storageService, never()).deleteObject(any(), any());
    }

    @Test
    void marks_error_on_unexpected_exception() {
        givenClaimed(); givenBytes();
        givenRows(1);
        when(orchestrator.moderate(any(), any(), anyBoolean(), anyInt())).thenThrow(new RuntimeException("unexpected"));

        service.processPhotoModeration(photoId, userId, "profile-photos/u/p.jpg", "PENDING", true, 0);

        verify(moderationResultService).markError(eq(photoId), eq("INTERNAL_ERROR"),
                eq("Unexpected error during moderation"),
                eq(List.of("INTERNAL_ERROR")),
                eq("Unexpected error during moderation"));
        verify(storageService, never()).deleteObject(any(), any());
    }

    @Test
    void skipped_decision_maps_to_approved() {
        givenClaimed(); givenBytes(); givenRows(1);
        when(orchestrator.moderate(any(), any(), anyBoolean(), anyInt())).thenReturn(ModerationDecision.skipped());

        service.processPhotoModeration(photoId, userId, "profile-photos/u/p.jpg", "PENDING", true, 0);

        verify(onboardingService).recomputeScore(userId);
        verify(storageService, never()).deleteObject(any(), any());
    }

    @Test
    void does_not_call_recompute_when_photo_already_past_pending() {
        givenClaimed(); givenBytes(); givenRows(0);
        when(orchestrator.moderate(any(), any(), anyBoolean(), anyInt()))
                .thenReturn(new ModerationDecision(ImageModerationStatus.APPROVED,
                        true, false, null, null, List.of(), null, null));

        service.processPhotoModeration(photoId, userId, "profile-photos/u/p.jpg", "PENDING", true, 0);

        verify(onboardingService, never()).recomputeScore(any());
    }

    // ── new tests ─────────────────────────────────────────────────────────────

    @Test
    void sync_moderation_throws_503_when_credentials_missing() {
        givenClaimed(); givenBytes();
        when(orchestrator.moderate(any(), any(), eq(true), anyInt()))
                .thenThrow(new RekognitionProviderException("CREDENTIALS_UNAVAILABLE", "missing AWS creds", false));

        assertThatThrownBy(() -> service.processPhotoModerationSync(photoId, userId, "profile-photos/u/p.jpg", true, 0))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(503));

        verify(moderationResultService, never()).markError(eq(photoId), anyString(), anyString(), any(), any());
    }

    @Test
    void secondary_photo_passes_isPrimary_false_to_orchestrator() {
        givenClaimed(); givenBytes(); givenRows(1);
        when(orchestrator.moderate(any(), any(), eq(false), anyInt()))
                .thenReturn(new ModerationDecision(ImageModerationStatus.APPROVED,
                        false, true, null, null, List.of(), null, null));

        service.processPhotoModeration(photoId, userId, "profile-photos/u/p.jpg", "PENDING", false, 0);

        // Must pass isPrimary=false so orchestrator skips face detection
        verify(orchestrator).moderate(any(), any(), eq(false), anyInt());
        verify(storageService, never()).deleteObject(any(), any());
    }

    @Test
    void rejected_secondary_photo_deleted_from_storage_and_profile_stays_active() {
        givenClaimed(); givenBytes(); givenRows(1);
        when(orchestrator.moderate(any(), any(), eq(false), anyInt()))
                .thenReturn(new ModerationDecision(ImageModerationStatus.REJECTED,
                        false, true, null, null,
                        List.of("EXPLICIT_CONTENT_DETECTED"),
                        "This photo could not be approved because it may contain nudity or sexual content.", null));

        service.processPhotoModeration(photoId, userId, "profile-photos/u/sec.jpg", "PENDING", false, 0);

        // Storage deleted
        verify(storageService).deleteObject(eq("profile-photos"), eq("u/sec.jpg"));
        // Onboarding recomputed (secondary photo rejection still updates status → score may change)
        verify(onboardingService).recomputeScore(userId);
    }

    @Test
    void manual_review_does_not_delete_from_storage() {
        givenClaimed(); givenBytes(); givenRows(1);
        when(orchestrator.moderate(any(), any(), anyBoolean(), anyInt()))
                .thenReturn(new ModerationDecision(ImageModerationStatus.MANUAL_REVIEW,
                        false, true, null, null,
                        List.of("SUGGESTIVE_CONTENT_REVIEW"), "This photo requires additional review.", null));

        service.processPhotoModeration(photoId, userId, "profile-photos/u/p.jpg", "PENDING", true, 0);

        // Photo kept in storage for admin review
        verify(storageService, never()).deleteObject(any(), any());
    }

    @Test
    void primary_photo_rejection_blocks_profile_via_onboarding_recompute() {
        givenClaimed(); givenBytes(); givenRows(1);
        when(orchestrator.moderate(any(), any(), eq(true), anyInt()))
                .thenReturn(new ModerationDecision(ImageModerationStatus.REJECTED,
                        true, false, null, null,
                        List.of("NO_FACE_DETECTED"), "The profile photo should have a clear and visible face.", null));

        service.processPhotoModeration(photoId, userId, "profile-photos/u/p.jpg", "PENDING", true, 0);

        // onboarding recompute runs — sets profile inactive because primary photo is REJECTED
        verify(onboardingService).recomputeScore(userId);
        verify(storageService).deleteObject(eq("profile-photos"), eq("u/p.jpg"));
    }

    @Test
    void sync_moderation_escalates_invalid_webp_to_manual_review() {
        givenClaimed();
        givenRows(1);
        when(storageService.downloadPhoto("profile-photos/u/p.webp")).thenReturn(new byte[]{1, 2, 3});
        InvalidModerationImageException failure = new InvalidModerationImageException("WEBP_INVALID", "Invalid WebP");
        when(moderationImageConverter.prepareForModeration(any())).thenThrow(failure);

        PhotoModerationService.SyncModerationOutcome outcome =
                service.processPhotoModerationSync(photoId, userId, "profile-photos/u/p.webp", true, 0);

        assertThat(outcome.status()).isEqualTo("MANUAL_REVIEW");
        assertThat(outcome.rejectionReason()).isEqualTo("Invalid WebP");

        verify(moderationResultService).markError(eq(photoId), eq("INVALID_IMAGE_FORMAT"), eq("Invalid WebP"),
                eq(List.of("INVALID_IMAGE_FORMAT")), eq("Invalid WebP"));
        verify(storageService, never()).deleteObject(any(), any());
        verify(onboardingService).recomputeScore(userId);
        verify(orchestrator, never()).moderate(any(), any(), anyBoolean(), anyInt());
    }

    @Test
    void async_moderation_escalates_invalid_webp_without_throwing() {
        givenClaimed();
        givenRows(1);
        when(storageService.downloadPhoto("profile-photos/u/p.webp")).thenReturn(new byte[]{1, 2, 3});
        InvalidModerationImageException failure = new InvalidModerationImageException("WEBP_ANIMATED", "Animated WebP");
        when(moderationImageConverter.prepareForModeration(any())).thenThrow(failure);

        service.processPhotoModeration(photoId, userId, "profile-photos/u/p.webp", "PENDING", true, 0);

        verify(moderationResultService).markError(eq(photoId), eq("INVALID_IMAGE_FORMAT"), eq("Animated WebP"),
                eq(List.of("INVALID_IMAGE_FORMAT")), eq("Animated WebP"));
        verify(storageService, never()).deleteObject(any(), any());
        verify(onboardingService).recomputeScore(userId);
        verify(orchestrator, never()).moderate(any(), any(), anyBoolean(), anyInt());
    }

    // ── AI recheck on promotion into eligible slots ───────────────────────────

    private void givenModule() {
        ReflectionTestUtils.setField(service, "aiDetectionModule", aiDetectionModule);
    }

    private void givenPhotoRow(String status, Boolean aiEnabled) {
        when(jdbc.queryForList(contains("WHERE pp.id = :photoId"), anyMap()))
                .thenReturn(List.of(Map.of(
                        "moderation_status", status,
                        "storage_bucket", "profile-photos",
                        "storage_path", "u/p.jpg",
                        "ai_detection_enabled", aiEnabled)));
    }

    private AiDetectionResult detected(AiDetectionAction action) {
        return new AiDetectionResult(true, 0.97, "midjourney", "ai", action);
    }

    @Test
    void recheck_noop_when_module_absent() {
        service.recheckAiDetectionIfUnchecked(photoId, userId, 1);
        verify(jdbc, never()).queryForList(anyString(), anyMap());
    }

    @Test
    void recheck_noop_when_order_ineligible() {
        givenModule();
        service.recheckAiDetectionIfUnchecked(photoId, userId, 2);
        verify(jdbc, never()).queryForList(anyString(), anyMap());
    }

    @Test
    void recheck_skips_already_checked_photo() {
        givenModule();
        givenPhotoRow("APPROVED", Boolean.TRUE);

        service.recheckAiDetectionIfUnchecked(photoId, userId, 1);

        verify(aiDetectionModule, never()).analyze(any(), anyInt());
        verify(storageService, never()).downloadPhoto(anyString());
    }

    @Test
    void recheck_skips_pending_photo() {
        givenModule();
        givenPhotoRow("PENDING", Boolean.FALSE);

        service.recheckAiDetectionIfUnchecked(photoId, userId, 1);

        verify(aiDetectionModule, never()).analyze(any(), anyInt());
    }

    @Test
    void recheck_persists_clean_result_without_status_change() {
        givenModule();
        givenPhotoRow("APPROVED", Boolean.FALSE);
        givenBytes();
        AiDetectionResult clean = new AiDetectionResult(false, 0.12, null, "human", AiDetectionAction.ADVISE_ONLY);
        when(aiDetectionModule.analyze(any(), eq(1))).thenReturn(clean);

        service.recheckAiDetectionIfUnchecked(photoId, userId, 1);

        verify(moderationResultService).updateAiDetectionResult(photoId, clean);
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
        verify(storageService, never()).deleteObject(any(), any());
    }

    @Test
    void recheck_detected_rejected_updates_photo_and_deletes_storage() {
        givenModule();
        givenPhotoRow("APPROVED", Boolean.FALSE);
        givenBytes();
        givenRows(1);
        when(aiDetectionModule.analyze(any(), eq(1))).thenReturn(detected(AiDetectionAction.REJECTED));

        service.recheckAiDetectionIfUnchecked(photoId, userId, 1);

        verify(moderationResultService).updateAiDetectionResult(eq(photoId), any());
        verify(storageService).deleteObject("profile-photos", "u/p.jpg");
        verify(onboardingService).recomputeScore(userId);
    }

    @Test
    void recheck_detected_manual_review_keeps_storage() {
        givenModule();
        givenPhotoRow("APPROVED", Boolean.FALSE);
        givenBytes();
        givenRows(1);
        when(aiDetectionModule.analyze(any(), eq(0))).thenReturn(detected(AiDetectionAction.MANUAL_REVIEW));

        service.recheckAiDetectionIfUnchecked(photoId, userId, 0);

        verify(moderationResultService).updateAiDetectionResult(eq(photoId), any());
        verify(storageService, never()).deleteObject(any(), any());
        verify(onboardingService).recomputeScore(userId);
    }

    @Test
    void recheck_advise_only_persists_but_does_not_enforce() {
        givenModule();
        givenPhotoRow("APPROVED", Boolean.FALSE);
        givenBytes();
        when(aiDetectionModule.analyze(any(), eq(1))).thenReturn(detected(AiDetectionAction.ADVISE_ONLY));

        service.recheckAiDetectionIfUnchecked(photoId, userId, 1);

        verify(moderationResultService).updateAiDetectionResult(eq(photoId), any());
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
        verify(storageService, never()).deleteObject(any(), any());
        verify(onboardingService, never()).recomputeScore(any());
    }

    @Test
    void recheck_swallows_provider_failure() {
        givenModule();
        givenPhotoRow("APPROVED", Boolean.FALSE);
        givenBytes();
        when(aiDetectionModule.analyze(any(), anyInt())).thenThrow(new RuntimeException("provider down"));

        service.recheckAiDetectionIfUnchecked(photoId, userId, 1);

        verify(moderationResultService, never()).updateAiDetectionResult(any(), any());
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void recheck_skips_when_download_fails() {
        givenModule();
        givenPhotoRow("APPROVED", Boolean.FALSE);
        when(storageService.downloadPhoto(anyString())).thenReturn(null);

        service.recheckAiDetectionIfUnchecked(photoId, userId, 1);

        verify(aiDetectionModule, never()).analyze(any(), anyInt());
    }

    @Test
    void top_two_sweep_rechecks_only_unchecked_photos() {
        givenModule();
        UUID checkedId   = UUID.randomUUID();
        UUID uncheckedId = UUID.randomUUID();
        when(jdbc.queryForList(contains("LIMIT 2"), anyMap()))
                .thenReturn(List.of(
                        Map.of("id", checkedId,   "ai_detection_enabled", Boolean.TRUE),
                        Map.of("id", uncheckedId, "ai_detection_enabled", Boolean.FALSE)));
        when(jdbc.queryForList(contains("WHERE pp.id = :photoId"), anyMap()))
                .thenReturn(List.of(Map.of(
                        "moderation_status", "APPROVED",
                        "storage_bucket", "profile-photos",
                        "storage_path", "u/p2.jpg",
                        "ai_detection_enabled", Boolean.FALSE)));
        givenBytes();
        when(aiDetectionModule.analyze(any(), eq(1)))
                .thenReturn(new AiDetectionResult(false, 0.05, null, "human", AiDetectionAction.ADVISE_ONLY));

        service.recheckUncheckedTopPhotos(userId);

        verify(aiDetectionModule, times(1)).analyze(any(), eq(1));
        verify(moderationResultService).updateAiDetectionResult(eq(uncheckedId), any());
    }
}
