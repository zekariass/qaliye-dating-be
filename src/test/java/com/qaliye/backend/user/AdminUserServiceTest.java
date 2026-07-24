package com.qaliye.backend.user;

import com.qaliye.backend.user.dto.AdminUserDetailDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock private NamedParameterJdbcTemplate jdbc;
    @Mock private UserStatusService userStatusService;
    @Mock private CacheManager cacheManager;
    @Mock private Cache cache;

    @InjectMocks
    private AdminUserService service;

    private UUID adminId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        adminId = UUID.randomUUID();
        userId = UUID.randomUUID();
    }

    @Test
    void listUsers_returnsPaginatedResults() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.<Object>of());
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(5L);

        Map<String, Object> result = service.listUsers("ACTIVE", "USER", null, 1, 20);

        assertEquals(5L, result.get("total"));
        assertEquals(1, result.get("page"));
        assertEquals(20, result.get("pageSize"));
        assertNotNull(result.get("users"));
    }

    @Test
    void getUserDetail_returnsDetailDto() {
        Map<String, Object> userRow = new HashMap<>();
        userRow.put("id", userId);
        userRow.put("display_name", "Test User");
        userRow.put("status", "ACTIVE");
        userRow.put("role", "USER");
        userRow.put("preferred_language", "en");
        userRow.put("last_active_at", Timestamp.from(Instant.now()));
        userRow.put("deleted_at", null);
        userRow.put("created_at", Timestamp.from(Instant.now()));
        userRow.put("updated_at", Timestamp.from(Instant.now()));
        userRow.put("gender", "MALE");
        userRow.put("residency_type", "ETHIOPIA");
        userRow.put("relationship_intention", "MARRIAGE");
        userRow.put("is_onboarded", true);
        userRow.put("is_verified", false);
        userRow.put("is_visible", true);
        userRow.put("profile_completion_score", 75);

        Map<String, Object> photoRow = new HashMap<>();
        photoRow.put("total", 3L);
        photoRow.put("pending", 1L);
        photoRow.put("approved", 1L);
        photoRow.put("rejected", 1L);
        photoRow.put("manual_review", 0L);

        Map<String, Object> reportRow = new HashMap<>();
        reportRow.put("total", 2L);
        reportRow.put("pending", 1L);

        when(jdbc.queryForList(anyString(), eq(Map.of("userId", userId))))
                .thenReturn(List.of(userRow))
                .thenReturn(List.of(photoRow))
                .thenReturn(List.of(reportRow));

        when(jdbc.query(anyString(), eq(Map.of("userId", userId)), any(RowMapper.class)))
                .thenReturn(List.of("APPROVED"));
        when(jdbc.queryForObject(anyString(), eq(Map.of("userId", userId)), eq(Long.class)))
                .thenReturn(4L);

        AdminUserDetailDto detail = service.getUserDetail(userId);

        assertEquals("Test User", detail.displayName());
        assertEquals("ACTIVE", detail.status());
        assertEquals(3, detail.photoCount());
        assertEquals(2, detail.reportCount());
        assertEquals("APPROVED", detail.verificationStatus());
        assertEquals(4, detail.activeMatchCount());
    }

    @Test
    void getUserDetail_throwsNotFound_whenUserMissing() {
        when(jdbc.queryForList(anyString(), eq(Map.of("userId", userId))))
                .thenReturn(List.of());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.getUserDetail(userId));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void updateUserStatus_succeeds_forAdmin() {
        when(userStatusService.getStatus(adminId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "ADMIN", "en"));
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(userId));
        when(cacheManager.getCache("userStatus")).thenReturn(cache);

        assertDoesNotThrow(() -> service.updateUserStatus(adminId, userId, "SUSPENDED", "spam"));

        verify(cache).evict(userId);
    }

    @Test
    void updateUserStatus_throwsForbidden_forNonAdmin() {
        when(userStatusService.getStatus(adminId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "USER", "en"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.updateUserStatus(adminId, userId, "SUSPENDED", null));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void updateUserStatus_throwsBadRequest_whenChangingOwnStatus() {
        when(userStatusService.getStatus(adminId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "ADMIN", "en"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.updateUserStatus(adminId, adminId, "SUSPENDED", null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void updateUserStatus_throwsNotFound_whenUserNotFoundOrSameStatus() {
        when(userStatusService.getStatus(adminId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "ADMIN", "en"));
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.updateUserStatus(adminId, userId, "SUSPENDED", null));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void updateUserRole_succeeds_forAdmin() {
        when(userStatusService.getStatus(adminId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "ADMIN", "en"));
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(userId));
        when(cacheManager.getCache("userStatus")).thenReturn(cache);

        assertDoesNotThrow(() -> service.updateUserRole(adminId, userId, "MODERATOR"));

        verify(cache).evict(userId);
    }

    @Test
    void updateUserRole_throwsBadRequest_whenChangingOwnRole() {
        when(userStatusService.getStatus(adminId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "ADMIN", "en"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.updateUserRole(adminId, adminId, "USER"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void updateUserRole_throwsForbidden_forNonAdmin() {
        when(userStatusService.getStatus(adminId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "MODERATOR", "en"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.updateUserRole(adminId, userId, "ADMIN"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }
}
