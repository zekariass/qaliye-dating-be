package com.qaliye.backend.profile;

import com.qaliye.backend.moderation.PhotoModerationService;
import com.qaliye.backend.profile.dto.PhotoReorderItem;
import com.qaliye.backend.profile.dto.PhotoReorderRequest;
import com.qaliye.backend.storage.SupabaseStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProfilePhotoReorderAiTest {

    @Mock NamedParameterJdbcTemplate jdbc;
    @Mock SupabaseStorageService storageService;
    @Mock PhotoModerationService photoModerationService;

    ProfilePhotoService service;

    UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ProfilePhotoService(jdbc, storageService, photoModerationService);

        Map<String, Object> photoRow = new LinkedHashMap<>();
        photoRow.put("moderation_status", "APPROVED");
        photoRow.put("storage_bucket", "profile-photos");
        photoRow.put("storage_path", "user/photo.jpg");
        photoRow.put("face_detection_enabled", true);
        photoRow.put("face_count", 1);

        when(jdbc.queryForList(anyString(), any(Map.class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("pg_advisory_xact_lock")) return List.of();
            if (sql.contains("moderation_status <> 'REJECTED'")) return List.of();
            return List.of(photoRow);
        });
        when(jdbc.update(anyString(), any(Map.class))).thenReturn(1);
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
    }

    @Test
    void ai_enforcement_called_for_all_photos_landing_in_positions_0_1_2() {
        UUID photoA = UUID.randomUUID();
        UUID photoB = UUID.randomUUID();
        UUID photoC = UUID.randomUUID();

        // sorted indices: A→0, B→1, C→2  (all within AI_ELIGIBLE_ORDER_MAX)
        PhotoReorderRequest request = new PhotoReorderRequest(List.of(
                new PhotoReorderItem(photoA, 0, true),
                new PhotoReorderItem(photoB, 5, false),
                new PhotoReorderItem(photoC, 10, false)
        ));

        service.reorderPhotos(userId, request);

        verify(photoModerationService).enforceAiDetectionForPromotion(eq(photoA), eq(userId), anyString(), eq(0));
        verify(photoModerationService).enforceAiDetectionForPromotion(eq(photoB), eq(userId), anyString(), eq(1));
        verify(photoModerationService).enforceAiDetectionForPromotion(eq(photoC), eq(userId), anyString(), eq(2));
    }

    @Test
    void ai_enforcement_skipped_for_photos_landing_beyond_position_2() {
        UUID photoA = UUID.randomUUID();
        UUID photoB = UUID.randomUUID();
        UUID photoC = UUID.randomUUID();
        UUID photoD = UUID.randomUUID(); // sorted index 3 — beyond AI threshold

        PhotoReorderRequest request = new PhotoReorderRequest(List.of(
                new PhotoReorderItem(photoA, 0, true),
                new PhotoReorderItem(photoB, 1, false),
                new PhotoReorderItem(photoC, 2, false),
                new PhotoReorderItem(photoD, 3, false)
        ));

        service.reorderPhotos(userId, request);

        verify(photoModerationService, times(3))
                .enforceAiDetectionForPromotion(any(), eq(userId), anyString(), anyInt());
        verify(photoModerationService, never())
                .enforceAiDetectionForPromotion(eq(photoD), any(), anyString(), anyInt());
    }

    @Test
    void reorder_fails_and_mutations_aborted_when_ai_enforcement_rejects_photo() {
        UUID photoId = UUID.randomUUID();

        doThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "This photo appears to be AI-generated. Please upload a real photo."))
                .when(photoModerationService)
                .enforceAiDetectionForPromotion(any(), any(), anyString(), anyInt());

        PhotoReorderRequest request = new PhotoReorderRequest(List.of(
                new PhotoReorderItem(photoId, 0, true)
        ));

        assertThatThrownBy(() -> service.reorderPhotos(userId, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(422));

        // The shift-order UPDATE must not have executed
        verify(jdbc, never()).update(contains("photo_order = photo_order + ("), any(Map.class));
    }
}
