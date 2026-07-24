package com.qaliye.backend.admin;

import com.qaliye.backend.moderation.PhotoModerationItemDto;
import com.qaliye.backend.storage.SupabaseStorageService;
import com.qaliye.backend.user.UserStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminModerationQueueServiceTest {

    @Mock private NamedParameterJdbcTemplate jdbc;
    @Mock private UserStatusService userStatusService;
    @Mock private SupabaseStorageService storageService;

    @InjectMocks
    private AdminModerationQueueService service;

    private UUID modId;

    @BeforeEach
    void setUp() {
        modId = UUID.randomUUID();
    }

    @Test
    void getManualReviewQueue_returnsItems_forModerator() {
        when(userStatusService.getStatus(modId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "MODERATOR", "en"));
        when(storageService.generateSignedUrl(anyString(), anyString(), anyInt()))
                .thenReturn("https://signed.url/photo.jpg");

        // Simulate jdbc.query calling the callback with one row
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(2);
            var rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
            when(rs.getObject("id", UUID.class)).thenReturn(UUID.randomUUID());
            when(rs.getObject("user_id", UUID.class)).thenReturn(UUID.randomUUID());
            when(rs.getString("storage_bucket")).thenReturn("profile-photos");
            when(rs.getString("storage_path")).thenReturn("user1/photo.jpg");
            when(rs.getString("moderation_status")).thenReturn("MANUAL_REVIEW");
            when(rs.getString("display_name")).thenReturn("Test User");
            when(rs.getTimestamp("created_at")).thenReturn(new java.sql.Timestamp(System.currentTimeMillis()));
            handler.processRow(rs);
            return null;
        }).when(jdbc).query(anyString(), eq(Map.of()), any(RowCallbackHandler.class));

        List<PhotoModerationItemDto> items = service.getManualReviewQueue(modId);

        assertEquals(1, items.size());
        assertEquals("MANUAL_REVIEW", items.get(0).moderationStatus());
        assertEquals("Test User", items.get(0).displayName());
    }

    @Test
    void getReviewQueue_returnsItems_forAdmin() {
        when(userStatusService.getStatus(modId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "ADMIN", "en"));
        when(storageService.generateSignedUrl(anyString(), anyString(), anyInt()))
                .thenReturn("https://signed.url/photo.jpg");

        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(2);
            var rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
            when(rs.getObject("id", UUID.class)).thenReturn(UUID.randomUUID());
            when(rs.getObject("user_id", UUID.class)).thenReturn(UUID.randomUUID());
            when(rs.getString("storage_bucket")).thenReturn("profile-photos");
            when(rs.getString("storage_path")).thenReturn("user1/photo.jpg");
            when(rs.getString("moderation_status")).thenReturn("PENDING");
            when(rs.getString("display_name")).thenReturn("Pending User");
            when(rs.getTimestamp("created_at")).thenReturn(new java.sql.Timestamp(System.currentTimeMillis()));
            handler.processRow(rs);
            return null;
        }).when(jdbc).query(anyString(), eq(Map.of()), any(RowCallbackHandler.class));

        List<PhotoModerationItemDto> items = service.getReviewQueue(modId);

        assertEquals(1, items.size());
        assertEquals("PENDING", items.get(0).moderationStatus());
    }

    @Test
    void getQueueCounts_returnsCounts_forModerator() {
        when(userStatusService.getStatus(modId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "MODERATOR", "en"));

        Map<String, Object> counts = new HashMap<>();
        counts.put("pending", 5L);
        counts.put("manual_review", 3L);
        counts.put("approved", 100L);
        counts.put("rejected", 10L);
        when(jdbc.queryForList(anyString(), eq(Map.of())))
                .thenReturn(List.of(counts));

        Map<String, Object> result = service.getQueueCounts(modId);

        assertEquals(5L, result.get("pending"));
        assertEquals(3L, result.get("manual_review"));
        assertEquals(100L, result.get("approved"));
        assertEquals(10L, result.get("rejected"));
    }

    @Test
    void getManualReviewQueue_throwsForbidden_forRegularUser() {
        when(userStatusService.getStatus(modId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "USER", "en"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.getManualReviewQueue(modId));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void getReviewQueue_throwsForbidden_forRegularUser() {
        when(userStatusService.getStatus(modId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "USER", "en"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.getReviewQueue(modId));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void getQueueCounts_throwsForbidden_forRegularUser() {
        when(userStatusService.getStatus(modId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "USER", "en"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.getQueueCounts(modId));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void getManualReviewQueue_throwsForbidden_whenUserNotFound() {
        when(userStatusService.getStatus(modId))
                .thenReturn(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.getManualReviewQueue(modId));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    // --- approvePhoto ---

    @Test
    void approvePhoto_updatesStatus_andWritesAuditLog() {
        UUID photoId = UUID.randomUUID();
        when(userStatusService.getStatus(modId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "ADMIN", "en"));
        Map<String, Object> photoRow = new HashMap<>();
        photoRow.put("deleted_at", null);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(List.of(photoRow));
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(1);

        Map<String, Object> result = service.approvePhoto(modId, photoId);

        assertEquals("APPROVED", result.get("moderationStatus"));
        assertEquals(photoId, result.get("photoId"));
        // Two updates: APPROVE_PHOTO_SQL + AUDIT_LOG_SQL
        verify(jdbc, times(2)).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void approvePhoto_throwsNotFound_whenPhotoDoesNotExist() {
        UUID photoId = UUID.randomUUID();
        when(userStatusService.getStatus(modId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "ADMIN", "en"));
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(List.of());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.approvePhoto(modId, photoId));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void approvePhoto_throwsNotFound_whenPhotoDeleted() {
        UUID photoId = UUID.randomUUID();
        when(userStatusService.getStatus(modId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "ADMIN", "en"));
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(List.of(Map.of("deleted_at", java.sql.Timestamp.valueOf("2026-01-01 00:00:00"))));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.approvePhoto(modId, photoId));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void approvePhoto_throwsConflict_whenAlreadyModerated() {
        UUID photoId = UUID.randomUUID();
        when(userStatusService.getStatus(modId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "ADMIN", "en"));
        Map<String, Object> photoRow = new HashMap<>();
        photoRow.put("deleted_at", null);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(List.of(photoRow));
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(0);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.approvePhoto(modId, photoId));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void approvePhoto_throwsForbidden_forRegularUser() {
        UUID photoId = UUID.randomUUID();
        when(userStatusService.getStatus(modId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "USER", "en"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.approvePhoto(modId, photoId));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    // --- rejectPhoto ---

    @Test
    void rejectPhoto_updatesStatus_andWritesAuditLog() {
        UUID photoId = UUID.randomUUID();
        when(userStatusService.getStatus(modId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "MODERATOR", "en"));
        Map<String, Object> photoRow = new HashMap<>();
        photoRow.put("deleted_at", null);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(List.of(photoRow));
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(1);

        Map<String, Object> result = service.rejectPhoto(modId, photoId, "Inappropriate content");

        assertEquals("REJECTED", result.get("moderationStatus"));
        assertEquals(photoId, result.get("photoId"));
        verify(jdbc, times(2)).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void rejectPhoto_worksWithNullReason() {
        UUID photoId = UUID.randomUUID();
        when(userStatusService.getStatus(modId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "ADMIN", "en"));
        Map<String, Object> photoRow = new HashMap<>();
        photoRow.put("deleted_at", null);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(List.of(photoRow));
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(1);

        Map<String, Object> result = service.rejectPhoto(modId, photoId, null);

        assertEquals("REJECTED", result.get("moderationStatus"));
    }

    @Test
    void rejectPhoto_throwsConflict_whenAlreadyModerated() {
        UUID photoId = UUID.randomUUID();
        when(userStatusService.getStatus(modId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "ADMIN", "en"));
        Map<String, Object> photoRow = new HashMap<>();
        photoRow.put("deleted_at", null);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(List.of(photoRow));
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(0);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.rejectPhoto(modId, photoId, "reason"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void rejectPhoto_throwsForbidden_forRegularUser() {
        UUID photoId = UUID.randomUUID();
        when(userStatusService.getStatus(modId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "USER", "en"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.rejectPhoto(modId, photoId, "reason"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }
}
