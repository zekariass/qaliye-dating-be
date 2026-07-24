package com.qaliye.backend.admin;

import com.qaliye.backend.user.AccountDeletionService;
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
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminAccountDeletionServiceTest {

    @Mock private AccountDeletionService accountDeletionService;
    @Mock private UserStatusService userStatusService;
    @Mock private NamedParameterJdbcTemplate jdbc;

    @InjectMocks
    private AdminAccountDeletionService service;

    private UUID adminId;
    private UUID targetUserId;

    @BeforeEach
    void setUp() {
        adminId = UUID.randomUUID();
        targetUserId = UUID.randomUUID();
    }

    @Test
    void deleteAccount_succeeds_forAdmin() {
        when(userStatusService.getStatus(adminId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "ADMIN", "en"));
        when(jdbc.queryForList(anyString(), eq(Map.of("userId", targetUserId))))
                .thenReturn(List.of(Map.of("status", "ACTIVE")));
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

        service.deleteAccount(adminId, targetUserId, "fake_profile");

        verify(accountDeletionService).deleteAccount(targetUserId);
        verify(jdbc).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void deleteAccount_isIdempotent_forAlreadyDeletedUser() {
        when(userStatusService.getStatus(adminId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "ADMIN", "en"));
        when(jdbc.queryForList(anyString(), eq(Map.of("userId", targetUserId))))
                .thenReturn(List.of(Map.of("status", "DELETED")));
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

        service.deleteAccount(adminId, targetUserId, "retry");

        verify(accountDeletionService).deleteAccount(targetUserId);
    }

    @Test
    void deleteAccount_throwsNotFound_whenUserMissing() {
        when(userStatusService.getStatus(adminId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "ADMIN", "en"));
        when(jdbc.queryForList(anyString(), eq(Map.of("userId", targetUserId))))
                .thenReturn(List.of());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.deleteAccount(adminId, targetUserId, null));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void deleteAccount_throwsBadRequest_whenDeletingSelf() {
        when(userStatusService.getStatus(adminId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "ADMIN", "en"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.deleteAccount(adminId, adminId, null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void deleteAccount_throwsForbidden_forNonAdmin() {
        when(userStatusService.getStatus(adminId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "USER", "en"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.deleteAccount(adminId, targetUserId, null));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void deleteAccount_throwsForbidden_forModerator() {
        when(userStatusService.getStatus(adminId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "MODERATOR", "en"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.deleteAccount(adminId, targetUserId, null));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void deleteAccount_worksWithNullReason() {
        when(userStatusService.getStatus(adminId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "ADMIN", "en"));
        when(jdbc.queryForList(anyString(), eq(Map.of("userId", targetUserId))))
                .thenReturn(List.of(Map.of("status", "ACTIVE")));
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

        service.deleteAccount(adminId, targetUserId, null);

        verify(accountDeletionService).deleteAccount(targetUserId);
    }
}
