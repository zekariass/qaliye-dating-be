package com.qaliye.backend.admin;

import com.qaliye.backend.admin.dto.AuditLogEntryDto;
import com.qaliye.backend.user.UserStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAuditLogServiceTest {

    @Mock private NamedParameterJdbcTemplate jdbc;
    @Mock private UserStatusService userStatusService;

    @InjectMocks
    private AdminAuditLogService service;

    private UUID adminId;
    private UUID actorId;

    @BeforeEach
    void setUp() {
        adminId = UUID.randomUUID();
        actorId = UUID.randomUUID();
    }

    @Test
    void listAuditLog_returnsPaginatedResults_forAdmin() {
        when(userStatusService.getStatus(adminId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "ADMIN", "en"));

        AuditLogEntryDto entry = new AuditLogEntryDto(
                UUID.randomUUID(), actorId, "Mod User", "PHOTO_MODERATION_REVIEWED",
                "profile_photos", UUID.randomUUID(), null, "{}",
                java.time.OffsetDateTime.now());
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(entry));
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(42L);

        Map<String, Object> result = service.listAuditLog(adminId, null, null, null, null, 1, 50);

        assertEquals(42L, result.get("total"));
        assertEquals(1, result.get("page"));
        assertEquals(50, result.get("pageSize"));
        @SuppressWarnings("unchecked")
        List<AuditLogEntryDto> entries = (List<AuditLogEntryDto>) result.get("entries");
        assertEquals(1, entries.size());
        assertEquals("Mod User", entries.get(0).actorDisplayName());
    }

    @Test
    void listAuditLog_filtersByAction() {
        when(userStatusService.getStatus(adminId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "ADMIN", "en"));
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(0L);

        Map<String, Object> result = service.listAuditLog(adminId, "USER_STATUS_CHANGED", null, null, null, 1, 20);

        assertEquals(0L, result.get("total"));
    }

    @Test
    void listAuditLog_filtersByActorId() {
        when(userStatusService.getStatus(adminId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "ADMIN", "en"));
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(0L);

        service.listAuditLog(adminId, null, null, actorId, null, 1, 20);

        verify(jdbc).query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class));
    }

    @Test
    void listAuditLog_throwsForbidden_forNonAdmin() {
        when(userStatusService.getStatus(adminId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "USER", "en"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.listAuditLog(adminId, null, null, null, null, 1, 20));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void listAuditLog_throwsForbidden_forModerator() {
        when(userStatusService.getStatus(adminId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "MODERATOR", "en"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.listAuditLog(adminId, null, null, null, null, 1, 20));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void listAuditLog_throwsForbidden_whenUserNotFound() {
        when(userStatusService.getStatus(adminId))
                .thenReturn(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.listAuditLog(adminId, null, null, null, null, 1, 20));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }
}
