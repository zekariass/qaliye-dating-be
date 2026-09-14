package com.qaliye.backend.discovery.service;

import com.qaliye.backend.activity.ActivityStatusService;
import com.qaliye.backend.billing.service.ActionCostService;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LikesServiceTest {

    @Mock NamedParameterJdbcTemplate jdbc;
    @Mock StorageSigningService signingService;
    @Mock ActivityStatusService activityStatusService;
    @Mock ActionCostService actionCostService;

    LikesService service;

    UUID userId = UUID.randomUUID();
    UUID ruleId  = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new LikesService(jdbc, signingService, activityStatusService, actionCostService);
        when(activityStatusService.now()).thenReturn(Instant.now());
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), any(Class.class)))
                .thenReturn(0L);
        lenient().when(actionCostService.getPlanRuleConfig(any(), anyString()))
                .thenReturn(new ActionCostService.PlanRuleConfig(ruleId, 0L, 0L, 1, "DAY", false));
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

    // ── Test 3: Fetching likes with a limited plan does NOT auto-reveal ────────
    @Test
    void getLikes_received_limitedPlan_neverCallsUpdate() {
        // Default stub from setUp: limitValue=1 → limited plan → no auto-reveal
        service.getLikes(userId, "RECEIVED", 0, 25);

        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void getLikes_sent_neverCallsUpdate() {
        service.getLikes(userId, "SENT", 0, 25);

        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    // ── Test: Unlimited-free plan auto-reveals all on RECEIVED page load ──────
    @Test
    void getLikes_received_unlimitedFree_autoRevealsAll() {
        when(actionCostService.getPlanRuleConfig(any(), anyString()))
                .thenReturn(new ActionCostService.PlanRuleConfig(ruleId, 0L, 0L, null, "DAY", false));

        service.getLikes(userId, "RECEIVED", 0, 25);

        verify(jdbc).update(anyString(), any(MapSqlParameterSource.class));
    }

    // ── Test: Unlimited-free does NOT auto-reveal on SENT direction ──────────
    @Test
    void getLikes_sent_unlimitedFree_noAutoReveal() {
        lenient().when(actionCostService.getPlanRuleConfig(any(), anyString()))
                .thenReturn(new ActionCostService.PlanRuleConfig(ruleId, 0L, 0L, null, "DAY", false));

        service.getLikes(userId, "SENT", 0, 25);

        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    // ── Test: No rule (ruleId=null) does NOT trigger auto-reveal ─────────────
    @Test
    void getLikes_received_noRule_noAutoReveal() {
        when(actionCostService.getPlanRuleConfig(any(), anyString()))
                .thenReturn(new ActionCostService.PlanRuleConfig(null, 0L, 0L, null, "DAY", false));

        service.getLikes(userId, "RECEIVED", 0, 25);

        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }
}
