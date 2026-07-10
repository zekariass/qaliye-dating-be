package com.qaliye.backend.billing.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BillingMarketResolverTest {

    @Mock NamedParameterJdbcTemplate jdbc;
    BillingMarketResolver resolver;

    UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        resolver = new BillingMarketResolver(jdbc);
    }

    @Test
    void resolveBillingCountry_returnsAdminSetCountry() {
        when(jdbc.queryForList(any(String.class), any(Map.class)))
                .thenReturn(List.of(Map.of("country_code", "ET")));

        String country = resolver.resolveBillingCountry(userId);

        assertThat(country).isEqualTo("ET");
    }

    @Test
    void resolveBillingCountry_noUser_returnsGlobal() {
        when(jdbc.queryForList(any(String.class), any(Map.class)))
                .thenReturn(List.of());

        String country = resolver.resolveBillingCountry(userId);

        assertThat(country).isEqualTo("GLOBAL");
    }

    @Test
    void resolveMarket_countryHasActiveMarket_returnsCountryMarket() {
        when(jdbc.queryForList(any(String.class), any(Map.class)))
                .thenReturn(List.of(Map.of("country_code", "ET")));
        stubHasActiveMarket("ET", "ANDROID", 2L, 2L);

        BillingMarketResolver.MarketResult result = resolver.resolveMarket(userId, "ANDROID");

        assertThat(result.billingCountryCode()).isEqualTo("ET");
        assertThat(result.resolvedCountryCode()).isEqualTo("ET");
        assertThat(result.platform()).isEqualTo("ANDROID");
        assertThat(result.fallbackToGlobal()).isFalse();
    }

    @Test
    void resolveMarket_countryHasNoMethods_fallsBackToGlobal() {
        when(jdbc.queryForList(any(String.class), any(Map.class)))
                .thenReturn(List.of(Map.of("country_code", "ET")));
        stubHasActiveMarket("ET", "ANDROID", 2L, 0L);
        stubHasActiveMarket("GLOBAL", "ANDROID", 3L, 1L);

        BillingMarketResolver.MarketResult result = resolver.resolveMarket(userId, "ANDROID");

        assertThat(result.billingCountryCode()).isEqualTo("ET");
        assertThat(result.resolvedCountryCode()).isEqualTo("GLOBAL");
        assertThat(result.fallbackToGlobal()).isTrue();
    }

    @Test
    void resolveMarket_countryHasNoOffers_fallsBackToGlobal() {
        when(jdbc.queryForList(any(String.class), any(Map.class)))
                .thenReturn(List.of(Map.of("country_code", "ET")));
        stubHasActiveMarket("ET", "ANDROID", 0L, 2L);
        stubHasActiveMarket("GLOBAL", "ANDROID", 3L, 1L);

        BillingMarketResolver.MarketResult result = resolver.resolveMarket(userId, "ANDROID");

        assertThat(result.resolvedCountryCode()).isEqualTo("GLOBAL");
        assertThat(result.fallbackToGlobal()).isTrue();
    }

    @Test
    void resolveMarket_alreadyGlobal_noFallbackFlag() {
        when(jdbc.queryForList(any(String.class), any(Map.class)))
                .thenReturn(List.of(Map.of("country_code", "GLOBAL")));
        stubHasActiveMarket("GLOBAL", "ANDROID", 3L, 2L);

        BillingMarketResolver.MarketResult result = resolver.resolveMarket(userId, "ANDROID");

        assertThat(result.resolvedCountryCode()).isEqualTo("GLOBAL");
        assertThat(result.fallbackToGlobal()).isFalse();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void stubHasActiveMarket(String country, String platform, long offerCount, long methodCount) {
        when(jdbc.queryForObject(
                contains("payment_offers"),
                eq(Map.of("countryCode", country, "platform", platform)),
                eq(Long.class)))
                .thenReturn(offerCount);
        if (offerCount > 0) {
            when(jdbc.queryForObject(
                    contains("payment_methods"),
                    eq(Map.of("countryCode", country, "platform", platform)),
                    eq(Long.class)))
                    .thenReturn(methodCount);
        }
    }
}
