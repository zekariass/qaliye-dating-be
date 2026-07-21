package com.qaliye.backend.billing.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Resolves the billing country and complete billing market for a user.
 *
 * Resolution order for billing country:
 *   1. app_users.billing_country_code  (admin-set, trusted)
 *   2. addresses.country_code          (from primary address)
 *   3. 'GLOBAL'                        (safe fallback)
 *
 * Market resolution:
 *   - resolveMarket: used for order creation. Requires both active offers AND methods
 *     for a country/platform before considering it a valid market. Falls back to GLOBAL.
 *   - resolveMethodsMarket: used for payment-method discovery (payment-channels,
 *     payment-options). Only checks whether methods exist for the country/platform.
 *     Falls back to GLOBAL if no methods exist. Does NOT require offers to be present.
 */
@Service
public class BillingMarketResolver {

    private static final Logger log = LoggerFactory.getLogger(BillingMarketResolver.class);

    private final NamedParameterJdbcTemplate jdbc;

    public BillingMarketResolver(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record MarketResult(
            String billingCountryCode,
            String resolvedCountryCode,
            String platform,
            boolean fallbackToGlobal
    ) {}

    public String resolveBillingCountry(UUID userId) {
        var results = jdbc.queryForList(
                """
                SELECT COALESCE(au.billing_country_code, a.country_code, 'GLOBAL') AS country_code
                FROM   app_users au
                LEFT JOIN addresses a ON a.id = au.address_id
                WHERE  au.id = :userId
                """,
                Map.of("userId", userId));
        if (results.isEmpty()) return "GLOBAL";
        Object cc = results.get(0).get("country_code");
        return cc != null ? cc.toString() : "GLOBAL";
    }

    /**
     * Resolves the promotion-specific country code for a user.
     * Returns the user's billing country if it is a known promotion market
     * (currently only "ET"), otherwise falls back to "GLOBAL".
     * This ensures ET users see only ET campaigns and non-ET users see only GLOBAL campaigns.
     */
    public String resolvePromotionCountry(UUID userId) {
        String country = resolveBillingCountry(userId);
        return "ET".equalsIgnoreCase(country) ? "ET" : "GLOBAL";
    }

    public MarketResult resolveMarket(UUID userId, String platform) {
        String billingCountry = resolveBillingCountry(userId);

        if (!"GLOBAL".equals(billingCountry) && hasActiveMarket(billingCountry, platform)) {
            log.debug("resolveMarket user={} → country={}, platform={}, fallback=false",
                    userId, billingCountry, platform);
            return new MarketResult(billingCountry, billingCountry, platform, false);
        }

        if (hasActiveMarket("GLOBAL", platform)) {
            boolean didFallback = !"GLOBAL".equals(billingCountry);
            log.debug("resolveMarket user={} → GLOBAL, platform={}, fallback={}", userId, platform, didFallback);
            return new MarketResult(billingCountry, "GLOBAL", platform, didFallback);
        }

        log.warn("resolveMarket user={} → no active market for platform={}", userId, platform);
        return new MarketResult(billingCountry, "GLOBAL", platform, true);
    }

    /**
     * Resolves the market for payment-method discovery only.
     * Falls back to GLOBAL if the user's country has no active payment methods.
     * Does NOT require active offers to exist for the country.
     */
    public MarketResult resolveMethodsMarket(UUID userId, String platform) {
        String billingCountry = resolveBillingCountry(userId);

        if (!"GLOBAL".equals(billingCountry) && hasActiveMethods(billingCountry, platform)) {
            log.debug("resolveMethodsMarket user={} → country={}, platform={}, fallback=false",
                    userId, billingCountry, platform);
            return new MarketResult(billingCountry, billingCountry, platform, false);
        }

        boolean didFallback = !"GLOBAL".equals(billingCountry);
        log.debug("resolveMethodsMarket user={} → GLOBAL, platform={}, fallback={}",
                userId, platform, didFallback);
        return new MarketResult(billingCountry, "GLOBAL", platform, didFallback);
    }

    private boolean hasActiveMarket(String countryCode, String platform) {
        var params = Map.of("countryCode", countryCode, "platform", platform);
        Long offerCount = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM payment_offers
                WHERE country_code = :countryCode AND platform = :platform AND is_active = TRUE
                """,
                params, Long.class);
        if (offerCount == null || offerCount == 0) return false;

        Long methodCount = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM payment_methods
                WHERE country_code = :countryCode AND platform = :platform AND is_active = TRUE
                """,
                params, Long.class);
        return methodCount != null && methodCount > 0;
    }

    private boolean hasActiveMethods(String countryCode, String platform) {
        var params = Map.of("countryCode", countryCode, "platform", platform);
        Long count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM payment_methods
                WHERE country_code = :countryCode AND platform = :platform AND is_active = TRUE
                """,
                params, Long.class);
        return count != null && count > 0;
    }
}
