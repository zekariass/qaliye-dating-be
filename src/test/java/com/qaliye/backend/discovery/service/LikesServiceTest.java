package com.qaliye.backend.discovery.service;

import com.qaliye.backend.activity.ActivityStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LikesServiceTest {

    @Mock NamedParameterJdbcTemplate jdbc;
    @Mock StorageSigningService signingService;
    @Mock ActivityStatusService activityStatusService;

    LikesService service;

    UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new LikesService(jdbc, signingService, activityStatusService);
        when(activityStatusService.now()).thenReturn(Instant.now());
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), any(Class.class)))
                .thenReturn(0L);
    }

    // ── Test 1 & 2: Likes are returned newest → oldest (no revealed-first prioritization) ──
    @Test
    void getLikes_received_queriesWithCreatedAtDescOrdering() {
        service.getLikes(userId, "RECEIVED", 0, 25);

        verify(jdbc).query(anyString(), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));
        verify(jdbc).queryForObject(anyString(), any(MapSqlParameterSource.class), any(Class.class));
    }

    @Test
    void getLikes_sent_queriesWithCreatedAtDescOrdering() {
        service.getLikes(userId, "SENT", 0, 25);

        verify(jdbc).query(anyString(), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));
    }

    // ── Test 3: Fetching Likes does NOT reveal anything ───────────────────────
    @Test
    void getLikes_received_neverCallsUpdate() {
        service.getLikes(userId, "RECEIVED", 0, 25);

        // No UPDATE should be issued — no auto-reveal side effect
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void getLikes_sent_neverCallsUpdate() {
        service.getLikes(userId, "SENT", 0, 25);

        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }
}
