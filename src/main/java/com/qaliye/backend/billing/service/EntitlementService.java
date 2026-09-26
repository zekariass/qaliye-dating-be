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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

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

    private static final String USAGE_SQL = """
            SELECT fa.code AS action_code, uat.used_count
            FROM user_action_limits_tracker uat
            JOIN subscription_plan_limit_and_cost splac
                ON splac.id = uat.subscription_plan_limit_and_cost_id
            JOIN feature_actions fa ON fa.id = splac.feature_action_id
            WHERE uat.user_id = :userId
              AND uat.period_start_date <= CURRENT_DATE
              AND uat.period_end_date   >= CURRENT_DATE
            """;

    private static final String PREMIUM_PLAN_LIMITS_SQL = """
            SELECT fa.code AS action_code, splac.limit_value
            FROM subscription_plan_limit_and_cost splac
            JOIN feature_actions fa ON fa.id = splac.feature_action_id
            JOIN subscription_plans sp ON sp.id = splac.subscription_plan_id
            WHERE sp.plan_kind = 'PAID'
              AND sp.is_active = TRUE
            ORDER BY CASE WHEN sp.country_code = :countryCode THEN 0 ELSE 1 END, fa.code
            """;

    private static final String PLAN_LIMITS_AND_COSTS_SQL = """
            SELECT fa.code AS action_code,
                   splac.member_credit_cost,
                   splac.actual_credit_cost,
                   splac.limit_value,
                   splac.period_type,
                   splac.apply_credit_after_limit,
                   splac.variant_pricing_enabled,
                   splac.variant_limits_enabled
            FROM subscription_plan_limit_and_cost splac
            JOIN feature_actions fa ON fa.id = splac.feature_action_id
            WHERE splac.subscription_plan_id = :planId
            """;

    private static final String VARIANT_LIMITS_AND_COSTS_SQL = """
            SELECT fa.code AS action_code,
                   afv.code AS variant_code,
                   spvlc.member_credit_cost,
                   spvlc.actual_credit_cost,
                   spvlc.limit_value,
                   spvlc.period_type,
                   spvlc.apply_credit_after_limit
            FROM subscription_plan_variant_limit_and_cost spvlc
            JOIN action_feature_variants afv ON afv.id = spvlc.action_feature_variant_id
            JOIN feature_actions fa ON fa.id = afv.feature_action_id
            WHERE spvlc.subscription_plan_id = :planId
            ORDER BY fa.code, afv.sort_order
            """;

    private static final String VARIANT_USAGE_SQL = """
            SELECT fa.code AS action_code, afv.code AS variant_code, uat.used_count
            FROM user_action_limits_tracker uat
            JOIN subscription_plan_variant_limit_and_cost spvlc
                ON spvlc.id = uat.subscription_plan_variant_limit_and_cost_id
            JOIN action_feature_variants afv ON afv.id = spvlc.action_feature_variant_id
            JOIN feature_actions fa ON fa.id = afv.feature_action_id
            WHERE uat.user_id = :userId
              AND uat.period_start_date <= CURRENT_DATE
              AND uat.period_end_date   >= CURRENT_DATE
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

        // Load current-period usage from tracker (all actions)
        Map<String, Integer> usageByAction = new LinkedHashMap<>();
        jdbc.query(USAGE_SQL, Map.of("userId", userId), rs -> {
            usageByAction.put(rs.getString("action_code"), rs.getInt("used_count"));
        });

        // Load all plan limits and costs in one query, merge with usage
        Map<String, EntitlementResponse.ActionLimitAndCost> limitsAndCosts = new LinkedHashMap<>();
        if (planId != null) {
            Instant subPeriodEnd = activeSub.map(BillingRepository.ActiveSubRow::periodEnd).orElse(null);
            jdbc.query(PLAN_LIMITS_AND_COSTS_SQL, Map.of("planId", planId), rs -> {
                String code = rs.getString("action_code");
                Object limitValObj = rs.getObject("limit_value");
                Integer limitVal = limitValObj != null ? ((Number) limitValObj).intValue() : null;
                String periodType = rs.getString("period_type");
                int used = usageByAction.getOrDefault(code, 0);
                Integer remaining = limitVal != null ? Math.max(0, limitVal - used) : null;
                Instant resetsAt = resolveResetsAt(periodType, subPeriodEnd);
                limitsAndCosts.put(code, new EntitlementResponse.ActionLimitAndCost(
                        used,
                        limitVal,
                        remaining,
                        resetsAt,
                        rs.getLong("member_credit_cost"),
                        rs.getLong("actual_credit_cost"),
                        periodType,
                        rs.getBoolean("apply_credit_after_limit"),
                        rs.getBoolean("variant_pricing_enabled"),
                        rs.getBoolean("variant_limits_enabled"),
                        null
                ));
            });
            attachVariantLimitsAndCosts(userId, planId, subPeriodEnd, limitsAndCosts);
        }

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

        return new EntitlementResponse(planCode, subInfo, limitsAndCosts, credits, boostInfo, features, planLimits,
                billingProps.getBoostDurationMinutes(), settingsDto);
    }

    private record VariantRuleRow(
            String actionCode, String variantCode, long memberCost, long actualCost,
            Integer limitValue, String periodType, boolean applyAfter
    ) {}

    /**
     * Attaches per-variant limit/cost entries to actions that have variant configuration
     * for the resolved plan. Each variant entry reports the <em>effective</em> values the
     * user would experience: costs come from the variant when variant pricing is enabled
     * (otherwise the action-level cost applies), and limits/usage come from the variant
     * when variant limits are enabled (otherwise the shared action-level limit applies).
     */
    private void attachVariantLimitsAndCosts(UUID userId, UUID planId, Instant subPeriodEnd,
                                             Map<String, EntitlementResponse.ActionLimitAndCost> limitsAndCosts) {
        List<VariantRuleRow> variantRules = new ArrayList<>();
        jdbc.query(VARIANT_LIMITS_AND_COSTS_SQL, Map.of("planId", planId), rs -> {
            Object limitValObj = rs.getObject("limit_value");
            variantRules.add(new VariantRuleRow(
                    rs.getString("action_code"),
                    rs.getString("variant_code"),
                    rs.getLong("member_credit_cost"),
                    rs.getLong("actual_credit_cost"),
                    limitValObj != null ? ((Number) limitValObj).intValue() : null,
                    rs.getString("period_type"),
                    rs.getBoolean("apply_credit_after_limit")
            ));
        });
        if (variantRules.isEmpty()) {
            return;
        }

        Map<String, Integer> variantUsageByCode = new LinkedHashMap<>();
        jdbc.query(VARIANT_USAGE_SQL, Map.of("userId", userId), rs -> {
            variantUsageByCode.put(rs.getString("action_code") + ":" + rs.getString("variant_code"),
                    rs.getInt("used_count"));
        });

        Map<String, List<VariantRuleRow>> rulesByAction = variantRules.stream()
                .collect(Collectors.groupingBy(VariantRuleRow::actionCode, LinkedHashMap::new, Collectors.toList()));

        for (Map.Entry<String, List<VariantRuleRow>> actionEntry : rulesByAction.entrySet()) {
            EntitlementResponse.ActionLimitAndCost base = limitsAndCosts.get(actionEntry.getKey());
            if (base == null) {
                continue;
            }

            Map<String, EntitlementResponse.VariantLimitAndCost> variants = new LinkedHashMap<>();
            for (VariantRuleRow rule : actionEntry.getValue()) {
                long memberCost = base.variantPricingEnabled() ? rule.memberCost() : base.memberCreditCost();
                long actualCost = base.variantPricingEnabled() ? rule.actualCost() : base.actualCreditCost();

                Integer limitVal;
                String periodType;
                boolean applyAfter;
                int used;
                if (base.variantLimitsEnabled()) {
                    limitVal = rule.limitValue();
                    periodType = rule.periodType();
                    applyAfter = rule.applyAfter();
                    used = variantUsageByCode.getOrDefault(
                            actionEntry.getKey() + ":" + rule.variantCode(), 0);
                } else {
                    limitVal = base.limit();
                    periodType = base.periodType();
                    applyAfter = base.applyCreditAfterLimit();
                    used = base.used();
                }

                Integer remaining = limitVal != null ? Math.max(0, limitVal - used) : null;
                Instant resetsAt = resolveResetsAt(periodType, subPeriodEnd);
                variants.put(rule.variantCode(), new EntitlementResponse.VariantLimitAndCost(
                        used, limitVal, remaining, resetsAt, memberCost, actualCost, periodType, applyAfter));
            }

            limitsAndCosts.put(actionEntry.getKey(), new EntitlementResponse.ActionLimitAndCost(
                    base.used(), base.limit(), base.remaining(), base.resetsAt(), base.memberCreditCost(),
                    base.actualCreditCost(), base.periodType(), base.applyCreditAfterLimit(),
                    base.variantPricingEnabled(), base.variantLimitsEnabled(), variants));
        }
    }

    private Instant resolveResetsAt(String periodType, Instant subPeriodEnd) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return switch (periodType != null ? periodType : "DAY") {
            case "MONTH" -> today.withDayOfMonth(today.lengthOfMonth())
                    .atTime(23, 59, 59)
                    .toInstant(ZoneOffset.UTC);
            case "BILLING_CYCLE" -> subPeriodEnd != null ? subPeriodEnd
                    : today.withDayOfMonth(today.lengthOfMonth())
                            .atTime(23, 59, 59)
                            .toInstant(ZoneOffset.UTC);
            default -> today.plusDays(1)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant();
        };
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
