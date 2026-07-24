package com.qaliye.backend.admin;

import com.qaliye.backend.notifications.repository.NotificationOutboxRepository;
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
class AdminPushNotificationServiceTest {

    @Mock private NotificationOutboxRepository outboxRepo;
    @Mock private NamedParameterJdbcTemplate jdbc;
    @Mock private UserStatusService userStatusService;

    @InjectMocks
    private AdminPushNotificationService service;

    private UUID adminId;
    private UUID targetUserId;

    @BeforeEach
    void setUp() {
        adminId = UUID.randomUUID();
        targetUserId = UUID.randomUUID();
    }

    @Test
    void sendPushNotification_succeeds_forAdmin() {
        when(userStatusService.getStatus(adminId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "ADMIN", "en"));
        when(jdbc.queryForList(anyString(), eq(Map.of("userId", targetUserId))))
                .thenReturn(List.of(Map.of("1", 1)));
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

        Map<String, Object> result = service.sendPushNotification(
                adminId, targetUserId, "Important", "Your account has been updated.");

        assertEquals("QUEUED", result.get("status"));
        assertEquals("ACCOUNT_ALERT", result.get("notificationType"));
        assertEquals(targetUserId, result.get("recipientUserId"));
        assertNotNull(result.get("eventId"));
        verify(outboxRepo).insert(any(), eq("ACCOUNT_ALERT"), eq(targetUserId), eq(adminId),
                any(), any(), any(), any(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void sendPushNotification_throwsNotFound_whenTargetUserDeleted() {
        when(userStatusService.getStatus(adminId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "ADMIN", "en"));
        when(jdbc.queryForList(anyString(), eq(Map.of("userId", targetUserId))))
                .thenReturn(List.of());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.sendPushNotification(adminId, targetUserId, "Test", "Test"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void sendPushNotification_throwsForbidden_forNonAdmin() {
        when(userStatusService.getStatus(adminId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "USER", "en"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.sendPushNotification(adminId, targetUserId, "Test", "Test"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void sendPushNotification_throwsForbidden_forModerator() {
        when(userStatusService.getStatus(adminId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "MODERATOR", "en"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.sendPushNotification(adminId, targetUserId, "Test", "Test"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void sendPushNotification_throwsForbidden_whenAdminNotFound() {
        when(userStatusService.getStatus(adminId))
                .thenReturn(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.sendPushNotification(adminId, targetUserId, "Test", "Test"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void sendPushNotification_throwsBadRequest_whenTargetUserIdNull() {
        when(userStatusService.getStatus(adminId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "ADMIN", "en"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.sendPushNotification(adminId, null, "Test", "Test"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void sendPushNotification_worksWithSpecialCharactersInBody() {
        when(userStatusService.getStatus(adminId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "ADMIN", "en"));
        when(jdbc.queryForList(anyString(), eq(Map.of("userId", targetUserId))))
                .thenReturn(List.of(Map.of("1", 1)));
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

        Map<String, Object> result = service.sendPushNotification(
                adminId, targetUserId, "Alert: \"Important\"", "Line 1\nLine 2");

        assertEquals("QUEUED", result.get("status"));
        verify(outboxRepo).insert(any(), eq("ACCOUNT_ALERT"), eq(targetUserId), eq(adminId),
                any(), any(), any(), any(), anyString(), anyString(), anyString(), any(), any());
    }
}
