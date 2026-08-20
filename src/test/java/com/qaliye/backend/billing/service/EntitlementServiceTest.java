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
import static org.mockito.Mockito.lenient;
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
}
