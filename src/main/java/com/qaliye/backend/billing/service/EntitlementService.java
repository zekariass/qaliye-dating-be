package com.qaliye.backend.billing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaliye.backend.billing.BillingProperties;
import com.qaliye.backend.billing.dto.EntitlementResponse;
import com.qaliye.backend.billing.repository.BillingRepository;
import com.qaliye.backend.billing.repository.CreditLotRepository;
import com.qaliye.backend.billing.service.CountrySettingsService;
import org.springframework.cache.annotation.Cacheable;
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
    private final CreditService creditService;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final BillingProperties billingProps;
    private final CountrySettingsService countrySettingsService;

    public EntitlementService(BillingRepository billingRepo,
                              CreditLotRepository creditLotRepo,
                              CreditService creditService,
                              NamedParameterJdbcTemplate jdbc,
                              ObjectMapper objectMapper,
                              BillingProperties billingProps,
                              CountrySettingsService countrySettingsService) {
        this.billingRepo = billingRepo;
        this.creditLotRepo = creditLotRepo;
        this.creditService = creditService;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.billingProps = billingProps;
        this.countrySettingsService = countrySettingsService;
    }

    private static final String PLAN_LIMITS_SQL = """
            SELECT fa.code AS action_code, splac.limit_value
            FROM subscription_plan_limit_and_cost splac
            JOIN feature_actions fa ON fa.id = splac.feature_action_id
            WHERE splac.subscription_plan_id = :planId
              AND fa.code IN ('LIKE', 'SUPER_LIKE', 'REWIND', 'BOOST',
                              'VOICE_MESSAGE', 'IMAGE_MESSAGE')
            """;

    private static final String DAILY_USAGE_SQL = """
            SELECT fa.code AS action_code, uat.used_count
            FROM user_action_limits_tracker uat
            JOIN subscription_plan_limit_and_cost splac
                ON splac.id = uat.subscription_plan_limit_and_cost_id
            JOIN feature_actions fa ON fa.id = splac.feature_action_id
            WHERE uat.user_id = :userId
              AND uat.period_start_date <= CURRENT_DATE
              AND uat.period_end_date   >= CURRENT_DATE
              AND fa.code IN ('LIKE', 'SUPER_LIKE', 'REWIND', 'BOOST',
                              'VOICE_MESSAGE', 'IMAGE_MESSAGE')
            """;

    private static final String PREMIUM_PLAN_LIMITS_SQL = """
            SELECT fa.code AS action_code, splac.limit_value
            FROM subscription_plan_limit_and_cost splac
            JOIN feature_actions fa ON fa.id = splac.feature_action_id
            JOIN subscription_plans sp ON sp.id = splac.subscription_plan_id
            WHERE sp.plan_kind = 'PAID'
              AND sp.is_active = TRUE
              AND fa.code IN ('LIKE', 'SUPER_LIKE', 'REWIND', 'BOOST',
                              'VOICE_MESSAGE', 'IMAGE_MESSAGE')
            ORDER BY CASE WHEN sp.country_code = :countryCode THEN 0 ELSE 1 END, fa.code
            """;

    public EntitlementResponse getEntitlements(UUID userId) {
        Optional<BillingRepository.ActiveSubRow> activeSub = billingRepo.findActiveSubscription(userId);

        String planCode;
        UUID planId;
        Map<String, Boolean> features;
        EntitlementResponse.SubscriptionInfo subInfo = null;

        if (activeSub.isPresent()) {
            var sub = activeSub.get();
            planCode = "PROMOTION".equals(sub.provider()) ? "FREE_PREMIUM" : sub.planCode();
            planId = sub.planId();
            features = parseFeatures(sub.features());
            subInfo = new EntitlementResponse.SubscriptionInfo(
                    sub.status(),
                    sub.provider(),
                    sub.billingIntervalCount(),
                    sub.billingIntervalUnit(),
                    sub.periodEnd(),
                    sub.autoRenew()
            );
        } else {
            planCode = "FREE";
            FreePlanInfo freePlan = getFreePlanInfo(userId);
            planId = freePlan.planId();
            features = freePlan.features();
        }

        // Load plan limits
        Map<String, Integer> limits = new LinkedHashMap<>();
        if (planId != null) {
            jdbc.query(PLAN_LIMITS_SQL, Map.of("planId", planId), rs -> {
                String code = rs.getString("action_code");
                Object val = rs.getObject("limit_value");
                Integer limitVal = val != null ? ((Number) val).intValue() : null;
                limits.put(code, limitVal);
            });
        }

        // Load current-period usage from tracker
        Map<String, Integer> usageByAction = new LinkedHashMap<>();
        jdbc.query(DAILY_USAGE_SQL, Map.of("userId", userId), rs -> {
            usageByAction.put(rs.getString("action_code"), rs.getInt("used_count"));
        });
        int likesUsed         = usageByAction.getOrDefault("LIKE",          0);
        int superLikesUsed    = usageByAction.getOrDefault("SUPER_LIKE",    0);
        int rewindsUsed       = usageByAction.getOrDefault("REWIND",        0);
        int boostsUsed        = usageByAction.getOrDefault("BOOST",         0);
        int voiceChatMsgsUsed = usageByAction.getOrDefault("VOICE_MESSAGE", 0);
        int imageChatMsgsUsed = usageByAction.getOrDefault("IMAGE_MESSAGE", 0);

        Instant tomorrowStart = LocalDate.now(ZoneOffset.UTC).plusDays(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant();

        // Resolve limits
        Integer likesLimit       = limits.get("LIKE");
        Integer superLikesLimit  = limits.get("SUPER_LIKE");
        Integer rewindsLimit     = limits.get("REWIND");
        Integer boostsLimit      = limits.get("BOOST");
        Integer voiceChatMsgLimit  = limits.get("VOICE_MESSAGE");
        Integer imageChatMsgLimit  = limits.get("IMAGE_MESSAGE");

        Map<String, EntitlementResponse.QuotaInfo> quotaMap = new LinkedHashMap<>();
        quotaMap.put("likes", buildQuota(likesUsed, likesLimit, tomorrowStart));
        quotaMap.put("superLikes", buildQuota(superLikesUsed, superLikesLimit, tomorrowStart));
        quotaMap.put("rewinds", buildQuota(rewindsUsed, rewindsLimit, tomorrowStart));
        quotaMap.put("boosts", buildQuota(boostsUsed, boostsLimit, null));
        quotaMap.put("voiceChatMsgs", buildQuota(voiceChatMsgsUsed, voiceChatMsgLimit, tomorrowStart));
        quotaMap.put("imageChatMsgs", buildQuota(imageChatMsgsUsed, imageChatMsgLimit, tomorrowStart));

        // Credits — all actions now use the central credit balance
        long centralCreditBalance = creditService.getBalance(userId);

        var credits = new EntitlementResponse.CreditsInfo(centralCreditBalance, 0, 0, 0);

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
                    String code = rs.getString("action_code");
                    Object val = rs.getObject("limit_value");
                    Integer limitVal = val != null ? ((Number) val).intValue() : null;
                    planLimits.putIfAbsent(code, limitVal);
                });

        var countrySettings = countrySettingsService.getSettingsForUser(userId);
        var settingsDto = new EntitlementResponse.CountrySettings(
                countrySettings.countryCode(),
                countrySettings.subscriptionEnabled(),
                countrySettings.creditsEnabled(),
                countrySettings.identityVerificationRequired()
        );

        return new EntitlementResponse(planCode, subInfo, quotaMap, credits, boostInfo, features, planLimits,
                billingProps.getBoostDurationMinutes(), settingsDto);
    }

    private EntitlementResponse.QuotaInfo buildQuota(int used, Integer limit, Instant resetsAt) {
        Integer remaining = limit != null ? Math.max(0, limit - used) : null;
        return new EntitlementResponse.QuotaInfo(used, limit, remaining, resetsAt);
    }

    private record FreePlanInfo(UUID planId, Map<String, Boolean> features) {}

    @Cacheable(value = "subscriptionFeatures", key = "'free-plan-' + #userId")
    public FreePlanInfo getFreePlanInfo(UUID userId) {
        String countryCode = billingRepo.getUserCountryCode(userId);
        var results = jdbc.queryForList("""
                SELECT id, features FROM subscription_plans
                WHERE plan_kind = 'FREE' AND is_active = TRUE
                ORDER BY CASE WHEN country_code = :cc THEN 0 ELSE 1 END
                LIMIT 1
                """, Map.of("cc", countryCode != null ? countryCode : "GLOBAL"));
        if (results.isEmpty()) {
            return new FreePlanInfo(null, defaultFreeFeatures());
        }
        var row = results.get(0);
        UUID planId = (UUID) row.get("id");
        Object featuresVal = row.get("features");
        String featuresJson = featuresVal != null ? featuresVal.toString() : null;
        Map<String, Boolean> features = parseFeatures(featuresJson);
        if (features.isEmpty()) {
            features = defaultFreeFeatures();
        }
        return new FreePlanInfo(planId, features);
    }

    private Map<String, Boolean> defaultFreeFeatures() {
        Map<String, Boolean> defaults = new LinkedHashMap<>();
        defaults.put("seeWhoLikedYou", false);
        defaults.put("advancedFilters", false);
        defaults.put("incognitoMode", false);
        return defaults;
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
