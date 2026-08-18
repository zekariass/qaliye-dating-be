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

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doNothing;
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
    }

    @Test
    void getEntitlements_freeUser_returnsFreeplan() {
        when(billingRepo.findActiveSubscription(userId)).thenReturn(Optional.empty());
        when(billingRepo.getUserCountryCode(userId)).thenReturn("ET");
        UUID freePlanId = UUID.randomUUID();
        when(jdbc.queryForList(contains("plan_kind = 'FREE'"), anyMap()))
                .thenReturn(List.of(Map.of("id", freePlanId, "features", "{\"seeWhoLikedYou\":false,\"advancedFilters\":false,\"incognitoMode\":true}")));
        doNothing().when(jdbc).query(contains("subscription_plan_limits"), anyMap(), any(RowCallbackHandler.class));
        when(jdbc.queryForList(contains("user_daily_limits"), anyMap()))
                .thenReturn(Collections.emptyList());
        when(creditLotRepo.getNonPurchasedBalance(userId, "BOOST_CREDIT")).thenReturn(0);
        when(creditLotRepo.getPurchasedBalance(userId, "BOOST_CREDIT")).thenReturn(0);
        when(creditLotRepo.getPurchasedBalance(userId, "SUPERLIKE_CREDIT")).thenReturn(0);
        when(creditLotRepo.getPurchasedBalance(userId, "REWIND_CREDIT")).thenReturn(0);
        when(creditLotRepo.findActiveBoost(userId)).thenReturn(Collections.emptyList());

        EntitlementResponse response = service.getEntitlements(userId);

        assertThat(response.plan()).isEqualTo("FREE");
        assertThat(response.subscription()).isNull();
        assertThat(response.features()).containsEntry("seeWhoLikedYou", false);
        assertThat(response.features()).containsEntry("incognitoMode", true);
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
        doNothing().when(jdbc).query(contains("subscription_plan_limits"), anyMap(), any(RowCallbackHandler.class));
        when(jdbc.queryForList(contains("user_daily_limits"), anyMap()))
                .thenReturn(List.of(Map.of("likes_used", 5, "super_likes_used", 1, "rewinds_used", 0)));
        when(creditLotRepo.getNonPurchasedBalance(userId, "BOOST_CREDIT")).thenReturn(0);
        when(creditLotRepo.getPurchasedBalance(userId, "BOOST_CREDIT")).thenReturn(2);
        when(creditLotRepo.getPurchasedBalance(userId, "SUPERLIKE_CREDIT")).thenReturn(5);
        when(creditLotRepo.getPurchasedBalance(userId, "REWIND_CREDIT")).thenReturn(3);
        when(creditLotRepo.findActiveBoost(userId)).thenReturn(Collections.emptyList());

        EntitlementResponse response = service.getEntitlements(userId);

        assertThat(response.plan()).isEqualTo("PREMIUM");
        assertThat(response.subscription()).isNotNull();
        assertThat(response.subscription().status()).isEqualTo("ACTIVE");
        assertThat(response.subscription().autoRenew()).isTrue();
        assertThat(response.credits().boostsAvailable()).isEqualTo(2);
        assertThat(response.credits().superLikesAvailable()).isEqualTo(5);
    }

    @Test
    void getEntitlements_withActiveBoost_includesBoostInfo() {
        when(billingRepo.findActiveSubscription(userId)).thenReturn(Optional.empty());
        when(billingRepo.getUserCountryCode(userId)).thenReturn("GLOBAL");
        when(jdbc.queryForList(contains("plan_kind = 'FREE'"), anyMap()))
                .thenReturn(List.of(Map.of("id", UUID.randomUUID(), "features", "{}")));
        doNothing().when(jdbc).query(contains("subscription_plan_limits"), anyMap(), any(RowCallbackHandler.class));
        when(jdbc.queryForList(contains("user_daily_limits"), anyMap()))
                .thenReturn(Collections.emptyList());
        when(creditLotRepo.getNonPurchasedBalance(userId, "BOOST_CREDIT")).thenReturn(0);
        when(creditLotRepo.getPurchasedBalance(userId, "BOOST_CREDIT")).thenReturn(0);
        when(creditLotRepo.getPurchasedBalance(userId, "SUPERLIKE_CREDIT")).thenReturn(0);
        when(creditLotRepo.getPurchasedBalance(userId, "REWIND_CREDIT")).thenReturn(0);

        Instant boostStart = Instant.now().minusSeconds(600);
        Instant boostEnd = Instant.now().plusSeconds(1200);
        when(creditLotRepo.findActiveBoost(userId))
                .thenReturn(List.of(new CreditLotRepository.ActiveBoostRow(
                        UUID.randomUUID(), boostStart, boostEnd)));

        EntitlementResponse response = service.getEntitlements(userId);

        assertThat(response.activeBoost()).isNotNull();
        assertThat(response.activeBoost().remainingSeconds()).isGreaterThan(0);
    }
}
