package com.qaliye.backend.discovery.service;

import com.qaliye.backend.activity.ActivityStatus;
import com.qaliye.backend.activity.ActivityStatusService;
import com.qaliye.backend.billing.repository.ActionFeatureVariantRepository;
import com.qaliye.backend.billing.repository.ActionFeatureVariantRepository.VariantRow;
import com.qaliye.backend.billing.service.ActionCostService;
import com.qaliye.backend.discovery.dto.LikeItemDto;
import com.qaliye.backend.discovery.dto.LikesPageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LikesServiceTest {

    @Mock NamedParameterJdbcTemplate jdbc;
    @Mock StorageSigningService signingService;
    @Mock ActivityStatusService activityStatusService;
    @Mock ActionCostService actionCostService;
    @Mock ActionFeatureVariantRepository variantRepo;

    LikesService service;

    UUID userId = UUID.randomUUID();
    UUID ruleId  = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new LikesService(jdbc, signingService, activityStatusService, actionCostService, variantRepo);
        when(activityStatusService.now()).thenReturn(Instant.now());
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), any(Class.class)))
                .thenReturn(0L);
        lenient().when(variantRepo.findAllByActionCode(anyString()))
                .thenReturn(Collections.emptyList());
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

    // ── Variant fields: named variant populates actionVariant (RECEIVED) ─────
    @Test
    void getLikes_received_namedVariant_populatesActionVariant() throws Exception {
        stubVariantCatalog(variantRow("ROSE", true));
        stubSingleLikeRow(likeRow("ROSE", true));

        LikesPageResponse response = service.getLikes(userId, "RECEIVED", 0, 20);

        assertEquals(1, response.items().size());
        LikeItemDto item = response.items().get(0);
        assertEquals("ROSE", item.actionVariantCode());
        assertNotNull(item.actionVariant());
        assertEquals("ROSE", item.actionVariant().code());
        assertEquals("Rose", item.actionVariant().name());
        assertEquals("Send a rose", item.actionVariant().description());
        assertEquals("https://cdn.qal.app/actions/rose.webp", item.actionVariant().icon());
    }

    // ── Variant fields: named variant populates actionVariant (SENT) ─────────
    @Test
    void getLikes_sent_namedVariant_populatesActionVariant() throws Exception {
        stubVariantCatalog(variantRow("FIRE", true));
        stubSingleLikeRow(likeRow("FIRE", false));

        LikesPageResponse response = service.getLikes(userId, "SENT", 0, 20);

        assertEquals(1, response.items().size());
        LikeItemDto item = response.items().get(0);
        assertEquals("FIRE", item.actionVariantCode());
        assertNotNull(item.actionVariant());
        assertEquals("FIRE", item.actionVariant().code());
        assertEquals("Fire", item.actionVariant().name());
    }

    // ── Variant fields: plain like without variant → both fields null ────────
    @Test
    void getLikes_received_plainLike_nullVariantFields() throws Exception {
        stubSingleLikeRow(likeRow(null, true));

        LikesPageResponse response = service.getLikes(userId, "RECEIVED", 0, 20);

        assertEquals(1, response.items().size());
        LikeItemDto item = response.items().get(0);
        assertNull(item.actionVariantCode());
        assertNull(item.actionVariant());
    }

    @Test
    void getLikes_sent_plainLike_nullVariantFields() throws Exception {
        stubSingleLikeRow(likeRow(null, false));

        LikesPageResponse response = service.getLikes(userId, "SENT", 0, 20);

        LikeItemDto item = response.items().get(0);
        assertNull(item.actionVariantCode());
        assertNull(item.actionVariant());
    }

    // ── Variant fields: deactivated variant still resolves display metadata ──
    @Test
    void getLikes_received_deactivatedVariant_stillResolves() throws Exception {
        stubVariantCatalog(variantRow("ROSE", false));
        stubSingleLikeRow(likeRow("ROSE", true));

        LikesPageResponse response = service.getLikes(userId, "RECEIVED", 0, 20);

        LikeItemDto item = response.items().get(0);
        assertEquals("ROSE", item.actionVariantCode());
        assertNotNull(item.actionVariant());
        assertEquals("Rose", item.actionVariant().name());
    }

    // ── Variant fields: stored code with no catalog row → variant null, code kept
    @Test
    void getLikes_received_variantRowMissing_codePreservedVariantNull() throws Exception {
        // variantRepo default stub returns empty list — simulates deleted variant row
        stubSingleLikeRow(likeRow("GHOST", true));

        LikesPageResponse response = service.getLikes(userId, "RECEIVED", 0, 20);

        LikeItemDto item = response.items().get(0);
        assertEquals("GHOST", item.actionVariantCode());
        assertNull(item.actionVariant());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private VariantRow variantRow(String code, boolean active) {
        return new VariantRow(
                UUID.randomUUID(), UUID.randomUUID(), "LIKE",
                code, capitalize(code), "Send a " + code.toLowerCase(),
                "https://cdn.qal.app/actions/" + code.toLowerCase() + ".webp",
                active, false, 1);
    }

    private String capitalize(String s) {
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    private void stubVariantCatalog(VariantRow... rows) {
        when(variantRepo.findAllByActionCode("LIKE")).thenReturn(List.of(rows));
    }

    private void stubSingleLikeRow(ResultSet rs) {
        when(activityStatusService.resolve(anyBoolean(), any(), any()))
                .thenReturn(ActivityStatus.RECENTLY_ACTIVE);
        doAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(2);
            handler.processRow(rs);
            return null;
        }).when(jdbc).query(anyString(), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));
    }

    /**
     * Builds a mock ResultSet matching the columns read by the likes row mapper.
     * {@code revealed_at} is only read for the RECEIVED direction.
     */
    private ResultSet likeRow(String variantCode, boolean received) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("action_id", UUID.class)).thenReturn(UUID.randomUUID());
        when(rs.getObject("other_user_id", UUID.class)).thenReturn(UUID.randomUUID());
        when(rs.getString("display_name")).thenReturn("Alice");
        when(rs.getInt("age")).thenReturn(28);
        when(rs.getBoolean("is_verified")).thenReturn(true);
        when(rs.getString("storage_bucket")).thenReturn(null);
        when(rs.getString("storage_path")).thenReturn(null);
        when(rs.getString("action_type")).thenReturn("LIKE");
        when(rs.getObject("created_at")).thenReturn(OffsetDateTime.now());
        when(rs.getDouble("distance_km")).thenReturn(0.0);
        when(rs.wasNull()).thenReturn(true);
        when(rs.getObject("last_active_at", OffsetDateTime.class)).thenReturn(null);
        when(rs.getBoolean("show_activity_status")).thenReturn(false);
        when(rs.getString("city")).thenReturn("Addis Ababa");
        when(rs.getString("region")).thenReturn("Addis Ababa");
        when(rs.getString("country_name")).thenReturn("Ethiopia");
        when(rs.getString("action_variant_code")).thenReturn(variantCode);
        if (received) {
            when(rs.getObject("revealed_at")).thenReturn(null);
        }
        return rs;
    }
}
