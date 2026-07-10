package com.qaliye.backend.billing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaliye.backend.billing.dto.EntitlementResponse;
import com.qaliye.backend.billing.repository.BillingRepository;
import com.qaliye.backend.billing.repository.CreditLotRepository;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class EntitlementService {

    private final BillingRepository billingRepo;
    private final CreditLotRepository creditLotRepo;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public EntitlementService(BillingRepository billingRepo,
                              CreditLotRepository creditLotRepo,
                              NamedParameterJdbcTemplate jdbc,
                              ObjectMapper objectMapper) {
        this.billingRepo = billingRepo;
        this.creditLotRepo = creditLotRepo;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    private static final String PLAN_LIMITS_SQL = """
            SELECT spl.limit_type, spl.limit_value
            FROM subscription_plan_limits spl
            WHERE spl.plan_id = :planId
              AND spl.limit_type IN ('LIKES', 'SUPERLIKES', 'REWINDS', 'BOOSTS')
            """;

    private static final String DAILY_USAGE_SQL = """
            SELECT likes_used, super_likes_used, rewinds_used
            FROM user_daily_limits
            WHERE user_id = :userId AND limit_date = (NOW() AT TIME ZONE 'UTC')::DATE
            """;

    private static final String PREMIUM_PLAN_LIMITS_SQL = """
            SELECT spl.limit_type, spl.limit_value
            FROM subscription_plan_limits spl
            JOIN subscription_plans sp ON sp.id = spl.plan_id
            WHERE sp.plan_kind = 'PAID'
              AND sp.is_active = TRUE
              AND spl.limit_type IN ('LIKES', 'SUPERLIKES', 'REWINDS', 'BOOSTS')
            ORDER BY CASE WHEN sp.country_code = :countryCode THEN 0 ELSE 1 END, spl.limit_type
            """;

    public EntitlementResponse getEntitlements(UUID userId) {
        Optional<BillingRepository.ActiveSubRow> activeSub = billingRepo.findActiveSubscription(userId);

        String planCode;
        UUID planId;
        Map<String, Boolean> features;
        EntitlementResponse.SubscriptionInfo subInfo = null;

        if (activeSub.isPresent()) {
            var sub = activeSub.get();
            planCode = sub.planCode();
            planId = sub.planId();
            features = parseFeatures(sub.features());
            subInfo = new EntitlementResponse.SubscriptionInfo(
                    sub.status(),
                    sub.billingIntervalCount(),
                    sub.billingIntervalUnit(),
                    sub.periodEnd(),
                    sub.autoRenew()
            );
        } else {
            planCode = "FREE";
            planId = getFreePlanId(userId);
            features = Map.of(
                    "seeWhoLikedYou", false,
                    "advancedFilters", false,
                    "incognitoMode", false
            );
        }

        // Load plan limits
        Map<String, Integer> limits = new LinkedHashMap<>();
        if (planId != null) {
            jdbc.query(PLAN_LIMITS_SQL, Map.of("planId", planId), rs -> {
                String type = rs.getString("limit_type");
                Object val = rs.getObject("limit_value");
                Integer limitVal = val != null ? ((Number) val).intValue() : null;
                limits.put(type, limitVal);
            });
        }

        // Load daily usage
        int likesUsed = 0, superLikesUsed = 0, rewindsUsed = 0;
        var usageRows = jdbc.queryForList(DAILY_USAGE_SQL, Map.of("userId", userId));
        if (!usageRows.isEmpty()) {
            var row = usageRows.get(0);
            likesUsed = ((Number) row.get("likes_used")).intValue();
            superLikesUsed = ((Number) row.get("super_likes_used")).intValue();
            rewindsUsed = ((Number) row.get("rewinds_used")).intValue();
        }

        Instant tomorrowStart = LocalDate.now(ZoneOffset.UTC).plusDays(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant();

        // Resolve limits
        Integer likesLimit = limits.get("LIKES");
        Integer superLikesLimit = limits.get("SUPERLIKES");
        Integer rewindsLimit = limits.get("REWINDS");
        Integer boostsLimit = limits.get("BOOSTS");

        Map<String, EntitlementResponse.QuotaInfo> quotaMap = new LinkedHashMap<>();
        quotaMap.put("likes", buildQuota(likesUsed, likesLimit, tomorrowStart));
        quotaMap.put("superLikes", buildQuota(superLikesUsed, superLikesLimit, tomorrowStart));
        quotaMap.put("rewinds", buildQuota(rewindsUsed, rewindsLimit, tomorrowStart));
        quotaMap.put("boosts", buildQuota(0, boostsLimit, null));

        // Credits
        int boostCredits = creditLotRepo.getBalance(userId, "BOOST_CREDIT");
        int superLikeCredits = creditLotRepo.getBalance(userId, "SUPERLIKE_CREDIT");
        int rewindCredits = creditLotRepo.getBalance(userId, "REWIND_CREDIT");

        var credits = new EntitlementResponse.CreditsInfo(boostCredits, superLikeCredits, rewindCredits);

        // Active boost
        EntitlementResponse.ActiveBoostInfo boostInfo = null;
        var activeBoosts = creditLotRepo.findActiveBoost(userId);
        if (!activeBoosts.isEmpty()) {
            var boost = activeBoosts.get(0);
            long remaining = Math.max(0, boost.expiresAt().getEpochSecond() - Instant.now().getEpochSecond());
            boostInfo = new EntitlementResponse.ActiveBoostInfo(boost.startedAt(), boost.expiresAt(), remaining);
        }

        // Load premium plan limits for "Go Premium" screen
        Map<String, Integer> planLimits = new LinkedHashMap<>();
        String countryCode = billingRepo.getUserCountryCode(userId);
        jdbc.query(PREMIUM_PLAN_LIMITS_SQL,
                Map.of("countryCode", countryCode != null ? countryCode : "GLOBAL"),
                rs -> {
                    String type = rs.getString("limit_type");
                    Object val = rs.getObject("limit_value");
                    Integer limitVal = val != null ? ((Number) val).intValue() : null;
                    planLimits.putIfAbsent(type, limitVal);
                });

        return new EntitlementResponse(planCode, subInfo, quotaMap, credits, boostInfo, features, planLimits);
    }

    private EntitlementResponse.QuotaInfo buildQuota(int used, Integer limit, Instant resetsAt) {
        Integer remaining = limit != null ? Math.max(0, limit - used) : null;
        return new EntitlementResponse.QuotaInfo(used, limit, remaining, resetsAt);
    }

    private UUID getFreePlanId(UUID userId) {
        String countryCode = billingRepo.getUserCountryCode(userId);
        var results = jdbc.queryForList("""
                SELECT id FROM subscription_plans
                WHERE plan_kind = 'FREE' AND is_active = TRUE
                ORDER BY CASE WHEN country_code = :cc THEN 0 ELSE 1 END
                LIMIT 1
                """, Map.of("cc", countryCode));
        return results.isEmpty() ? null : (UUID) results.get(0).get("id");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Boolean> parseFeatures(String featuresJson) {
        if (featuresJson == null || featuresJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(featuresJson, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
