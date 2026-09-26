package com.qaliye.backend.billing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaliye.backend.billing.BillingProperties;
import com.qaliye.backend.billing.dto.EntitlementResponse;
import com.qaliye.backend.billing.repository.BillingRepository;
import com.qaliye.backend.billing.repository.CreditLotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EntitlementServiceTest {

    @Mock BillingRepository billingRepo;
    @Mock CreditLotRepository creditLotRepo;
    @Mock CreditService creditService;
    @Mock NamedParameterJdbcTemplate jdbc;
    @Mock CountrySettingsService countrySettingsService;

    EntitlementService service;
    ObjectMapper objectMapper = new ObjectMapper();
    BillingProperties billingProps = new BillingProperties();

    UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        billingProps.setBoostDurationMinutes(60);
        service = new EntitlementService(billingRepo, creditLotRepo, creditService, jdbc, objectMapper, billingProps, countrySettingsService);
        lenient().doNothing().when(jdbc).query(anyString(), anyMap(), any(RowCallbackHandler.class));
        lenient().when(countrySettingsService.getSettingsForUser(any()))
                .thenReturn(new CountrySettingsService.CountrySettings("ET", true, true, false));
        lenient().when(creditService.getBalance(any())).thenReturn(0L);
        lenient().when(billingRepo.getUserCountryCode(any())).thenReturn("ET");
    }

    @Test
    void getEntitlements_freeUser_returnsFreeplan() {
        when(billingRepo.findActiveSubscription(userId)).thenReturn(Optional.empty());
        UUID freePlanId = UUID.randomUUID();
        when(jdbc.queryForList(contains("plan_kind = 'FREE'"), anyMap()))
                .thenReturn(List.of(Map.of("id", freePlanId, "features", "{\"seeWhoLikedYou\":false,\"advancedFilters\":false}")));
        when(creditLotRepo.findActiveBoost(userId)).thenReturn(Collections.emptyList());

        EntitlementResponse response = service.getEntitlements(userId);

        assertThat(response.plan()).isEqualTo("FREE");
        assertThat(response.subscription()).isNull();
        assertThat(response.features()).containsEntry("seeWhoLikedYou", false);
        assertThat(response.features()).doesNotContainKey("incognitoMode");
    }

    @Test
    void getEntitlements_premiumUser_returnsPremiumPlan() {
        UUID planId = UUID.randomUUID();
        Instant periodEnd = Instant.now().plusSeconds(86400 * 30);
        var activeSub = new BillingRepository.ActiveSubRow(
                UUID.randomUUID(), planId, "ACTIVE", true,
                Instant.now(), periodEnd,
                "STRIPE", "PREMIUM", "{\"seeWhoLikedYou\":true,\"advancedFilters\":true}",
                "MONTH", 1
        );

        when(billingRepo.findActiveSubscription(userId)).thenReturn(Optional.of(activeSub));
        when(creditLotRepo.findActiveBoost(userId)).thenReturn(Collections.emptyList());

        EntitlementResponse response = service.getEntitlements(userId);

        assertThat(response.plan()).isEqualTo("PREMIUM");
        assertThat(response.subscription()).isNotNull();
        assertThat(response.subscription().status()).isEqualTo("ACTIVE");
        assertThat(response.subscription().autoRenew()).isTrue();
    }

    @Test
    void getEntitlements_withActiveBoost_includesBoostInfo() {
        when(billingRepo.findActiveSubscription(userId)).thenReturn(Optional.empty());
        when(jdbc.queryForList(contains("plan_kind = 'FREE'"), anyMap()))
                .thenReturn(List.of(Map.of("id", UUID.randomUUID(), "features", "{}")));

        Instant boostStart = Instant.now().minusSeconds(600);
        Instant boostEnd = Instant.now().plusSeconds(1200);
        when(creditLotRepo.findActiveBoost(userId))
                .thenReturn(List.of(new CreditLotRepository.ActiveBoostRow(
                        UUID.randomUUID(), boostStart, boostEnd)));

        EntitlementResponse response = service.getEntitlements(userId);

        assertThat(response.activeBoost()).isNotNull();
        assertThat(response.activeBoost().remainingSeconds()).isGreaterThan(0);
    }

    private BillingRepository.ActiveSubRow activeSub(UUID planId) {
        return new BillingRepository.ActiveSubRow(
                UUID.randomUUID(), planId, "ACTIVE", true,
                Instant.now(), Instant.now().plusSeconds(86400 * 30),
                "STRIPE", "PREMIUM", "{}",
                "MONTH", 1
        );
    }

    private ResultSet actionRuleRs(String actionCode, int limitValue, long memberCost, long actualCost,
                                   boolean variantPricing, boolean variantLimits) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("action_code")).thenReturn(actionCode);
        when(rs.getObject("limit_value")).thenReturn(limitValue);
        when(rs.getString("period_type")).thenReturn("DAY");
        when(rs.getLong("member_credit_cost")).thenReturn(memberCost);
        when(rs.getLong("actual_credit_cost")).thenReturn(actualCost);
        when(rs.getBoolean("apply_credit_after_limit")).thenReturn(true);
        when(rs.getBoolean("variant_pricing_enabled")).thenReturn(variantPricing);
        when(rs.getBoolean("variant_limits_enabled")).thenReturn(variantLimits);
        return rs;
    }

    private ResultSet variantRuleRs(String actionCode, String variantCode, long memberCost, long actualCost,
                                    Integer limitValue, boolean applyAfter) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("action_code")).thenReturn(actionCode);
        when(rs.getString("variant_code")).thenReturn(variantCode);
        when(rs.getLong("member_credit_cost")).thenReturn(memberCost);
        when(rs.getLong("actual_credit_cost")).thenReturn(actualCost);
        when(rs.getObject("limit_value")).thenReturn(limitValue);
        when(rs.getString("period_type")).thenReturn("DAY");
        when(rs.getBoolean("apply_credit_after_limit")).thenReturn(applyAfter);
        return rs;
    }

    private ResultSet variantUsageRs(String actionCode, String variantCode, int used) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("action_code")).thenReturn(actionCode);
        when(rs.getString("variant_code")).thenReturn(variantCode);
        when(rs.getInt("used_count")).thenReturn(used);
        return rs;
    }

    @Test
    void getEntitlements_variantPricingEnabled_usesVariantCostsWithSharedLimit() throws SQLException {
        UUID planId = UUID.randomUUID();
        when(billingRepo.findActiveSubscription(userId)).thenReturn(Optional.of(activeSub(planId)));
        when(creditLotRepo.findActiveBoost(userId)).thenReturn(Collections.emptyList());

        doAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(2);
            handler.processRow(actionRuleRs("LIKE", 10, 1, 2, true, false));
            return null;
        }).when(jdbc).query(contains("variant_pricing_enabled"), anyMap(), any(RowCallbackHandler.class));

        doAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(2);
            handler.processRow(variantRuleRs("LIKE", "HEART", 1, 2, null, true));
            handler.processRow(variantRuleRs("LIKE", "ROSE", 10, 10, 5, false));
            return null;
        }).when(jdbc).query(contains("ORDER BY fa.code, afv.sort_order"), anyMap(), any(RowCallbackHandler.class));

        EntitlementResponse response = service.getEntitlements(userId);

        EntitlementResponse.ActionLimitAndCost like = response.limitsAndCosts().get("LIKE");
        assertThat(like).isNotNull();
        assertThat(like.variantPricingEnabled()).isTrue();
        assertThat(like.variantLimitsEnabled()).isFalse();
        assertThat(like.variants()).containsKeys("HEART", "ROSE");

        EntitlementResponse.VariantLimitAndCost heart = like.variants().get("HEART");
        assertThat(heart.memberCreditCost()).isEqualTo(1);
        assertThat(heart.actualCreditCost()).isEqualTo(2);

        EntitlementResponse.VariantLimitAndCost rose = like.variants().get("ROSE");
        assertThat(rose.memberCreditCost()).isEqualTo(10);
        assertThat(rose.actualCreditCost()).isEqualTo(10);
        // variant_limits_enabled = FALSE → shared LIKE limit/usage apply to every variant
        assertThat(rose.limit()).isEqualTo(10);
        assertThat(rose.used()).isEqualTo(0);
        assertThat(rose.remaining()).isEqualTo(10);
    }

    @Test
    void getEntitlements_variantLimitsEnabled_usesVariantLimitsAndUsage() throws SQLException {
        UUID planId = UUID.randomUUID();
        when(billingRepo.findActiveSubscription(userId)).thenReturn(Optional.of(activeSub(planId)));
        when(creditLotRepo.findActiveBoost(userId)).thenReturn(Collections.emptyList());

        doAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(2);
            handler.processRow(actionRuleRs("LIKE", 10, 1, 2, true, true));
            return null;
        }).when(jdbc).query(contains("variant_pricing_enabled"), anyMap(), any(RowCallbackHandler.class));

        doAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(2);
            handler.processRow(variantRuleRs("LIKE", "ROSE", 10, 10, 5, false));
            return null;
        }).when(jdbc).query(contains("ORDER BY fa.code, afv.sort_order"), anyMap(), any(RowCallbackHandler.class));

        doAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(2);
            handler.processRow(variantUsageRs("LIKE", "ROSE", 2));
            return null;
        }).when(jdbc).query(contains("uat.subscription_plan_variant_limit_and_cost_id"), anyMap(), any(RowCallbackHandler.class));

        EntitlementResponse response = service.getEntitlements(userId);

        EntitlementResponse.VariantLimitAndCost rose = response.limitsAndCosts().get("LIKE").variants().get("ROSE");
        assertThat(rose).isNotNull();
        assertThat(rose.limit()).isEqualTo(5);
        assertThat(rose.used()).isEqualTo(2);
        assertThat(rose.remaining()).isEqualTo(3);
        assertThat(rose.applyCreditAfterLimit()).isFalse();
    }

    @Test
    void getEntitlements_variantPricingDisabled_variantsMirrorActionCosts() throws SQLException {
        UUID planId = UUID.randomUUID();
        when(billingRepo.findActiveSubscription(userId)).thenReturn(Optional.of(activeSub(planId)));
        when(creditLotRepo.findActiveBoost(userId)).thenReturn(Collections.emptyList());

        doAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(2);
            handler.processRow(actionRuleRs("LIKE", 10, 3, 4, false, false));
            return null;
        }).when(jdbc).query(contains("variant_pricing_enabled"), anyMap(), any(RowCallbackHandler.class));

        doAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(2);
            handler.processRow(variantRuleRs("LIKE", "ROSE", 10, 10, 5, false));
            return null;
        }).when(jdbc).query(contains("ORDER BY fa.code, afv.sort_order"), anyMap(), any(RowCallbackHandler.class));

        EntitlementResponse response = service.getEntitlements(userId);

        EntitlementResponse.VariantLimitAndCost rose = response.limitsAndCosts().get("LIKE").variants().get("ROSE");
        assertThat(rose).isNotNull();
        assertThat(rose.memberCreditCost()).isEqualTo(3);
        assertThat(rose.actualCreditCost()).isEqualTo(4);
    }
}
