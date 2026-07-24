package com.qaliye.backend.admin;

import com.qaliye.backend.user.UserStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminAnalyticsServiceTest {

    @Mock private NamedParameterJdbcTemplate jdbc;
    @Mock private UserStatusService userStatusService;

    @InjectMocks
    private AdminAnalyticsService service;

    private UUID modId;

    @BeforeEach
    void setUp() {
        modId = UUID.randomUUID();
    }

    @Test
    void getDashboard_returnsAllSections_forAdmin() {
        when(userStatusService.getStatus(modId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "ADMIN", "en"));

        Map<String, Object> userStats = new HashMap<>();
        userStats.put("total_users", 1000L);
        userStats.put("active_users", 800L);
        when(jdbc.queryForList(anyString(), eq(Map.of())))
                .thenReturn(List.of(userStats))
                .thenReturn(List.of(new HashMap<>(Map.of("total_profiles", 950L))))
                .thenReturn(List.of(new HashMap<>(Map.of("total_matches", 500L))))
                .thenReturn(List.of(new HashMap<>(Map.of("photos_pending", 5L))))
                .thenReturn(List.of(new HashMap<>(Map.of("reports_pending", 3L))))
                .thenReturn(List.of(new HashMap<>(Map.of("verified_orders", 200L))))
                .thenReturn(List.of(new HashMap<>(Map.of("revenue_24h", 5000L))))
                .thenReturn(List.of(new HashMap<>(Map.of("active_subscriptions", 150L))))
                .thenReturn(List.of(new HashMap<>(Map.of("notifications_pending", 10L))));

        Map<String, Object> dashboard = service.getDashboard(modId);

        assertNotNull(dashboard.get("users"));
        assertNotNull(dashboard.get("profiles"));
        assertNotNull(dashboard.get("matches"));
        assertNotNull(dashboard.get("moderation"));
        assertNotNull(dashboard.get("reports"));
        assertNotNull(dashboard.get("revenue"));
        assertNotNull(dashboard.get("subscriptions"));
        assertNotNull(dashboard.get("notifications"));

        @SuppressWarnings("unchecked")
        Map<String, Object> users = (Map<String, Object>) dashboard.get("users");
        assertEquals(1000L, users.get("total_users"));

        @SuppressWarnings("unchecked")
        Map<String, Object> revenue = (Map<String, Object>) dashboard.get("revenue");
        assertEquals(200L, revenue.get("verified_orders"));
        assertEquals(5000L, revenue.get("revenue_24h"));
    }

    @Test
    void getDashboard_returnsAllSections_forModerator() {
        when(userStatusService.getStatus(modId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "MODERATOR", "en"));
        when(jdbc.queryForList(anyString(), eq(Map.of())))
                .thenReturn(List.of(new HashMap<>()))
                .thenReturn(List.of(new HashMap<>()))
                .thenReturn(List.of(new HashMap<>()))
                .thenReturn(List.of(new HashMap<>()))
                .thenReturn(List.of(new HashMap<>()))
                .thenReturn(List.of(new HashMap<>()))
                .thenReturn(List.of(new HashMap<>()))
                .thenReturn(List.of(new HashMap<>()))
                .thenReturn(List.of(new HashMap<>()));

        Map<String, Object> dashboard = service.getDashboard(modId);

        assertNotNull(dashboard.get("users"));
    }

    @Test
    void getDashboard_throwsForbidden_forRegularUser() {
        when(userStatusService.getStatus(modId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "USER", "en"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.getDashboard(modId));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void getDashboard_throwsForbidden_whenUserNotFound() {
        when(userStatusService.getStatus(modId))
                .thenReturn(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.getDashboard(modId));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void getDashboard_handlesEmptyResultGracefully() {
        when(userStatusService.getStatus(modId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "ADMIN", "en"));
        when(jdbc.queryForList(anyString(), eq(Map.of())))
                .thenReturn(List.of());

        Map<String, Object> dashboard = service.getDashboard(modId);

        assertNotNull(dashboard.get("users"));
        @SuppressWarnings("unchecked")
        Map<String, Object> users = (Map<String, Object>) dashboard.get("users");
        assertTrue(users.isEmpty());
    }
}
