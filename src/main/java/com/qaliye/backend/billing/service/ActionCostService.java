package com.qaliye.backend.billing.service;

import com.qaliye.backend.billing.repository.ActionLimitRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Evaluates the credit cost of a feature action for a user based on:
 *   1. The user's effective subscription plan (paid or FREE fallback).
 *   2. The configured subscription_plan_limit_and_cost rule.
 *   3. Current period usage from user_action_limits_tracker.
 *
 * This service does NOT perform credit deduction or tracker updates.
 * It returns an {@link ActionCostResult} describing what would happen.
 */
@Service
public class ActionCostService {

    private static final Logger log = LoggerFactory.getLogger(ActionCostService.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final ActionLimitRepository limitRepo;

    public ActionCostService(NamedParameterJdbcTemplate jdbc,
                             ActionLimitRepository limitRepo) {
        this.jdbc = jdbc;
        this.limitRepo = limitRepo;
    }

    /**
     * Result of an action cost evaluation.
     *
     * @param ruleId                    ID of the subscription_plan_limit_and_cost row
     * @param creditCost                Credits to deduct (0 means free)
     * @param allowanceAvailable        Whether the subscription allowance was available
     * @param allowanceExhausted        Whether the subscription limit was exhausted
     * @param actionBlocked             Whether the action is blocked (limit exhausted + apply_credit_after_limit=false)
     * @param periodStart               Start of the current tracking period
     * @param periodEnd                 End of the current tracking period
     * @param currentUsedCount          Current used_count before this action
     * @param limitValue                Configured limit (null = unlimited)
     * @param periodType                The period type (DAY, MONTH, BILLING_CYCLE)
     */
    public record ActionCostResult(
            UUID ruleId,
            long creditCost,
            boolean allowanceAvailable,
            boolean allowanceExhausted,
            boolean actionBlocked,
            LocalDate periodStart,
            LocalDate periodEnd,
            int currentUsedCount,
            Integer limitValue,
            String periodType
    ) {
        public boolean requiresCredits() { return creditCost > 0; }
        public boolean isBlocked() { return actionBlocked; }
    }

    private static final String RESOLVE_PLAN_RULE_SQL = """
            WITH effective_plan AS (
                SELECT sp.id AS plan_id, sp.plan_kind
                FROM user_subscriptions us
                JOIN subscription_plans sp ON sp.id = us.plan_id
                WHERE us.user_id = :userId
                  AND us.status IN ('ACTIVE', 'PENDING_VERIFICATION')
                  AND sp.is_active = TRUE
                ORDER BY CASE sp.plan_kind WHEN 'PAID' THEN 0 ELSE 1 END
                LIMIT 1
            ),
            free_plan AS (
                SELECT id AS plan_id, plan_kind
                FROM subscription_plans
                WHERE plan_code = 'FREE' AND country_code = 'GLOBAL' AND is_active = TRUE
                LIMIT 1
            ),
            resolved_plan AS (
                SELECT * FROM effective_plan
                UNION ALL
                SELECT * FROM free_plan
                WHERE NOT EXISTS (SELECT 1 FROM effective_plan)
                LIMIT 1
            )
            SELECT splac.id AS rule_id,
                   splac.member_credit_cost,
                   splac.actual_credit_cost,
                   splac.limit_value,
                   splac.period_type,
                   splac.apply_credit_after_limit,
                   rp.plan_kind,
                   us.current_period_start,
                   us.current_period_end
            FROM resolved_plan rp
            JOIN subscription_plan_limit_and_cost splac
                ON splac.subscription_plan_id = rp.plan_id
            JOIN feature_actions fa ON fa.id = splac.feature_action_id
            LEFT JOIN user_subscriptions us
                ON us.user_id = :userId
               AND us.status IN ('ACTIVE', 'PENDING_VERIFICATION')
               AND us.plan_id = rp.plan_id
            WHERE fa.code = :actionCode
            LIMIT 1
            """;

    /**
     * Raw plan rule configuration — limit, costs, period type — without consulting
     * any usage tracker. Used by callers that manage their own tracking (e.g. per-recipient
     * LIFETIME tracking in MessageCommandService).
     *
     * @param ruleId               ID of the subscription_plan_limit_and_cost row (null = no rule)
     * @param memberCreditCost     Credit cost when within subscription allowance (0 = free)
     * @param actualCreditCost     Credit cost after the limit is exhausted
     * @param limitValue           Configured limit (null = unlimited)
     * @param periodType           DAY / MONTH / BILLING_CYCLE / LIFETIME
     * @param applyCreditAfterLimit Whether credits can be charged once the limit is exhausted
     */
    public record PlanRuleConfig(
            UUID ruleId,
            long memberCreditCost,
            long actualCreditCost,
            Integer limitValue,
            String periodType,
            boolean applyCreditAfterLimit
    ) {}

    private static final String RESOLVE_PLAN_CONFIG_SQL = """
            WITH effective_plan AS (
                SELECT sp.id AS plan_id, sp.plan_kind
                FROM user_subscriptions us
                JOIN subscription_plans sp ON sp.id = us.plan_id
                WHERE us.user_id = :userId
                  AND us.status IN ('ACTIVE', 'PENDING_VERIFICATION')
                  AND sp.is_active = TRUE
                ORDER BY CASE sp.plan_kind WHEN 'PAID' THEN 0 ELSE 1 END
                LIMIT 1
            ),
            free_plan AS (
                SELECT id AS plan_id, plan_kind
                FROM subscription_plans
                WHERE plan_code = 'FREE' AND country_code = 'GLOBAL' AND is_active = TRUE
                LIMIT 1
            ),
            resolved_plan AS (
                SELECT * FROM effective_plan
                UNION ALL
                SELECT * FROM free_plan
                WHERE NOT EXISTS (SELECT 1 FROM effective_plan)
                LIMIT 1
            )
            SELECT splac.id AS rule_id,
                   splac.member_credit_cost,
                   splac.actual_credit_cost,
                   splac.limit_value,
                   splac.period_type,
                   splac.apply_credit_after_limit
            FROM resolved_plan rp
            JOIN subscription_plan_limit_and_cost splac
                ON splac.subscription_plan_id = rp.plan_id
            JOIN feature_actions fa ON fa.id = splac.feature_action_id
            WHERE fa.code = :actionCode
            LIMIT 1
            """;

    /**
     * Resolves the plan rule configuration for the given action without reading any
     * usage tracker. Callers that need usage-aware decisions should use
     * {@link #evaluate(UUID, String)} instead.
     */
    public PlanRuleConfig getPlanRuleConfig(UUID userId, String actionCode) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("actionCode", actionCode);

        List<PlanRuleConfig> results = jdbc.query(RESOLVE_PLAN_CONFIG_SQL, params, (rs, rn) -> {
            UUID ruleId         = rs.getObject("rule_id", UUID.class);
            long memberCost     = rs.getLong("member_credit_cost");
            boolean memberNull  = rs.wasNull();
            long actualCost     = rs.getLong("actual_credit_cost");
            boolean actualNull  = rs.wasNull();
            Object limitValObj  = rs.getObject("limit_value");
            Integer limitValue  = limitValObj != null ? ((Number) limitValObj).intValue() : null;
            String periodType   = rs.getString("period_type");
            boolean applyAfter  = rs.getBoolean("apply_credit_after_limit");

            if (memberNull && actualNull) { memberCost = 0; actualCost = 0; }
            else if (memberNull)          { memberCost = actualCost; }
            else if (actualNull)          { actualCost = memberCost; }

            return new PlanRuleConfig(ruleId, memberCost, actualCost, limitValue, periodType, applyAfter);
        });

        if (results.isEmpty()) {
            log.warn("No plan rule config found for user={} action={}; defaulting free", userId, actionCode);
            return new PlanRuleConfig(null, 0, 0, null, "DAY", false);
        }
        return results.get(0);
    }

    public ActionCostResult evaluate(UUID userId, String actionCode) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("actionCode", actionCode);

        List<ActionCostResult> results = jdbc.query(RESOLVE_PLAN_RULE_SQL, params, (rs, rn) -> {
            UUID ruleId             = rs.getObject("rule_id", UUID.class);
            long memberCost         = rs.getLong("member_credit_cost");
            boolean memberWasNull   = rs.wasNull();
            long actualCost         = rs.getLong("actual_credit_cost");
            boolean actualWasNull   = rs.wasNull();
            Object limitValObj      = rs.getObject("limit_value");
            Integer limitValue      = limitValObj != null ? ((Number) limitValObj).intValue() : null;
            String periodType       = rs.getString("period_type");
            boolean applyAfter      = rs.getBoolean("apply_credit_after_limit");
            Object subPeriodStart   = rs.getObject("current_period_start");
            Object subPeriodEnd     = rs.getObject("current_period_end");

            // Apply default cost values:
            // - If actual_credit_cost is NULL, default to member_credit_cost
            // - If member_credit_cost is NULL, default to actual_credit_cost
            // - If both are NULL, default to 0
            if (memberWasNull && actualWasNull) {
                memberCost = 0;
                actualCost = 0;
            } else if (memberWasNull) {
                memberCost = actualCost;
            } else if (actualWasNull) {
                actualCost = memberCost;
            }

            LocalDate[] period = resolvePeriod(periodType, subPeriodStart, subPeriodEnd);
            LocalDate periodStart = period[0];
            LocalDate periodEnd   = period[1];

            if (limitValue == null) {
                // Unlimited — subscription allowance always available, member cost applies
                return new ActionCostResult(ruleId, memberCost, true, false, false,
                        periodStart, periodEnd, 0, null, periodType);
            }

            // Check current usage — find the tracker that matches the current period
            Optional<ActionLimitRepository.TrackerRow> tracker =
                    limitRepo.find(userId, ruleId, periodStart);
            int usedCount = tracker.map(ActionLimitRepository.TrackerRow::usedCount).orElse(0);

            // If no tracker for the current period, check if there's a tracker from a
            // previous period type whose period hasn't expired yet — carry over its usage
            // so users can't bypass limits by changing period_type mid-period.
            if (tracker.isEmpty()) {
                Optional<ActionLimitRepository.TrackerRow> latest = limitRepo.findLatest(userId, ruleId);
                if (latest.isPresent()) {
                    ActionLimitRepository.TrackerRow row = latest.get();
                    LocalDate today = LocalDate.now();
                    if (row.periodEndDate() != null && !today.isAfter(row.periodEndDate())) {
                        usedCount = row.usedCount();
                    }
                }
            }

            boolean allowanceAvailable = usedCount < limitValue;

            if (allowanceAvailable) {
                return new ActionCostResult(ruleId, memberCost, true, false, false,
                        periodStart, periodEnd, usedCount, limitValue, periodType);
            }

            // Allowance exhausted
            if (!applyAfter) {
                return new ActionCostResult(ruleId, 0, false, true, true,
                        periodStart, periodEnd, usedCount, limitValue, periodType);
            }

            return new ActionCostResult(ruleId, actualCost, false, true, false,
                    periodStart, periodEnd, usedCount, limitValue, periodType);
        });

        if (results.isEmpty()) {
            log.warn("No action cost rule found for user={} action={}; defaulting free", userId, actionCode);
            LocalDate today = LocalDate.now();
            return new ActionCostResult(null, 0, true, false, false,
                    today, today, 0, null, "DAY");
        }

        return results.get(0);
    }

    // ── Variant-aware evaluation (LIKE variants: HEART, ROSE, BUNA, ...) ──────

    /**
     * Result of evaluating the cost/limit of a variant action (e.g. LIKE + ROSE).
     *
     * @param trackerRuleId       ID used for usage tracking — either the action-level
     *                            subscription_plan_limit_and_cost row id, or the
     *                            variant-level subscription_plan_variant_limit_and_cost
     *                            row id, depending on {@code variantScopedLimit}.
     * @param variantScopedLimit  Whether {@code trackerRuleId} refers to a variant-level
     *                            rule (tracked via ActionLimitRepository's variant methods)
     *                            rather than the shared action-level rule.
     */
    public record VariantCostResult(
            UUID trackerRuleId,
            boolean variantScopedLimit,
            long creditCost,
            boolean allowanceAvailable,
            boolean allowanceExhausted,
            boolean actionBlocked,
            LocalDate periodStart,
            LocalDate periodEnd,
            int currentUsedCount,
            Integer limitValue,
            String periodType
    ) {
        public boolean requiresCredits() { return creditCost > 0; }
        public boolean isBlocked() { return actionBlocked; }
    }

    /**
     * Thrown when an action's plan configuration enables variant pricing and/or
     * variant limits ({@code variant_pricing_enabled} / {@code variant_limits_enabled})
     * but no corresponding {@code subscription_plan_variant_limit_and_cost} row exists
     * for the resolved plan + variant. Fails safe rather than silently defaulting to
     * a free or unlimited action.
     */
    public static class VariantPricingNotConfiguredException extends RuntimeException {
        public VariantPricingNotConfiguredException(String message) {
            super(message);
        }
    }

    private record ResolvedActionRule(
            UUID ruleId, long memberCost, long actualCost, Integer limitValue, String periodType,
            boolean applyAfter, boolean variantPricingEnabled, boolean variantLimitsEnabled,
            UUID planId, Object subPeriodStart, Object subPeriodEnd
    ) {}

    private record ResolvedVariantRule(
            UUID variantRuleId, long memberCost, long actualCost, Integer limitValue,
            String periodType, boolean applyAfter
    ) {}

    private static final String RESOLVE_ACTION_RULE_WITH_VARIANT_FLAGS_SQL = """
            WITH effective_plan AS (
                SELECT sp.id AS plan_id, sp.plan_kind
                FROM user_subscriptions us
                JOIN subscription_plans sp ON sp.id = us.plan_id
                WHERE us.user_id = :userId
                  AND us.status IN ('ACTIVE', 'PENDING_VERIFICATION')
                  AND sp.is_active = TRUE
                ORDER BY CASE sp.plan_kind WHEN 'PAID' THEN 0 ELSE 1 END
                LIMIT 1
            ),
            free_plan AS (
                SELECT id AS plan_id, plan_kind
                FROM subscription_plans
                WHERE plan_code = 'FREE' AND country_code = 'GLOBAL' AND is_active = TRUE
                LIMIT 1
            ),
            resolved_plan AS (
                SELECT * FROM effective_plan
                UNION ALL
                SELECT * FROM free_plan
                WHERE NOT EXISTS (SELECT 1 FROM effective_plan)
                LIMIT 1
            )
            SELECT splac.id AS rule_id,
                   splac.member_credit_cost,
                   splac.actual_credit_cost,
                   splac.limit_value,
                   splac.period_type,
                   splac.apply_credit_after_limit,
                   splac.variant_pricing_enabled,
                   splac.variant_limits_enabled,
                   rp.plan_id,
                   us.current_period_start,
                   us.current_period_end
            FROM resolved_plan rp
            JOIN subscription_plan_limit_and_cost splac
                ON splac.subscription_plan_id = rp.plan_id
            JOIN feature_actions fa ON fa.id = splac.feature_action_id
            LEFT JOIN user_subscriptions us
                ON us.user_id = :userId
               AND us.status IN ('ACTIVE', 'PENDING_VERIFICATION')
               AND us.plan_id = rp.plan_id
            WHERE fa.code = :actionCode
            LIMIT 1
            """;

    private static final String RESOLVE_VARIANT_RULE_SQL = """
            SELECT id AS variant_rule_id, member_credit_cost, actual_credit_cost,
                   limit_value, period_type, apply_credit_after_limit
            FROM subscription_plan_variant_limit_and_cost
            WHERE subscription_plan_id = :planId
              AND action_feature_variant_id = :variantId
            LIMIT 1
            """;

    private ResolvedActionRule resolveActionRuleWithFlags(UUID userId, String actionCode) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("actionCode", actionCode);
        List<ResolvedActionRule> results = jdbc.query(RESOLVE_ACTION_RULE_WITH_VARIANT_FLAGS_SQL, params, (rs, rn) -> {
            UUID ruleId            = rs.getObject("rule_id", UUID.class);
            long memberCost        = rs.getLong("member_credit_cost");
            boolean memberWasNull  = rs.wasNull();
            long actualCost        = rs.getLong("actual_credit_cost");
            boolean actualWasNull  = rs.wasNull();
            Object limitValObj     = rs.getObject("limit_value");
            Integer limitValue     = limitValObj != null ? ((Number) limitValObj).intValue() : null;
            String periodType      = rs.getString("period_type");
            boolean applyAfter     = rs.getBoolean("apply_credit_after_limit");
            boolean variantPricing = rs.getBoolean("variant_pricing_enabled");
            boolean variantLimits  = rs.getBoolean("variant_limits_enabled");
            UUID planId            = rs.getObject("plan_id", UUID.class);
            Object subPeriodStart  = rs.getObject("current_period_start");
            Object subPeriodEnd    = rs.getObject("current_period_end");

            if (memberWasNull && actualWasNull) {
                memberCost = 0;
                actualCost = 0;
            } else if (memberWasNull) {
                memberCost = actualCost;
            } else if (actualWasNull) {
                actualCost = memberCost;
            }

            return new ResolvedActionRule(ruleId, memberCost, actualCost, limitValue, periodType,
                    applyAfter, variantPricing, variantLimits, planId, subPeriodStart, subPeriodEnd);
        });
        return results.isEmpty() ? null : results.get(0);
    }

    private ResolvedVariantRule resolveVariantRule(UUID planId, UUID variantId) {
        var params = new MapSqlParameterSource()
                .addValue("planId", planId)
                .addValue("variantId", variantId);
        List<ResolvedVariantRule> results = jdbc.query(RESOLVE_VARIANT_RULE_SQL, params, (rs, rn) -> {
            UUID variantRuleId    = rs.getObject("variant_rule_id", UUID.class);
            long memberCost       = rs.getLong("member_credit_cost");
            boolean memberWasNull = rs.wasNull();
            long actualCost       = rs.getLong("actual_credit_cost");
            boolean actualWasNull = rs.wasNull();
            Object limitValObj    = rs.getObject("limit_value");
            Integer limitValue    = limitValObj != null ? ((Number) limitValObj).intValue() : null;
            String periodType     = rs.getString("period_type");
            boolean applyAfter    = rs.getBoolean("apply_credit_after_limit");

            if (memberWasNull && actualWasNull) {
                memberCost = 0;
                actualCost = 0;
            } else if (memberWasNull) {
                memberCost = actualCost;
            } else if (actualWasNull) {
                actualCost = memberCost;
            }

            return new ResolvedVariantRule(variantRuleId, memberCost, actualCost, limitValue, periodType, applyAfter);
        });
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * Evaluates the cost/limit of a variant action (e.g. LIKE + ROSE) for a user.
     * <p>
     * Resolves the action-level rule (subscription_plan_limit_and_cost) for
     * {@code actionCode} first. If that rule has {@code variant_pricing_enabled}
     * and/or {@code variant_limits_enabled}, the corresponding values are instead
     * resolved from subscription_plan_variant_limit_and_cost for {@code variantId}.
     * Fails with {@link VariantPricingNotConfiguredException} rather than silently
     * defaulting to free/unlimited when a required variant configuration is missing.
     */
    public VariantCostResult evaluateVariant(UUID userId, String actionCode, UUID variantId) {
        ResolvedActionRule actionRule = resolveActionRuleWithFlags(userId, actionCode);
        if (actionRule == null) {
            log.warn("No plan rule configured for user={} action={}; defaulting free", userId, actionCode);
            LocalDate today = LocalDate.now();
            return new VariantCostResult(null, false, 0, true, false, false,
                    today, today, 0, null, "DAY");
        }

        long memberCost           = actionRule.memberCost();
        long actualCost           = actionRule.actualCost();
        Integer limitValue        = actionRule.limitValue();
        String periodType         = actionRule.periodType();
        boolean applyAfter        = actionRule.applyAfter();
        UUID trackerRuleId        = actionRule.ruleId();
        boolean variantScopedLimit = false;

        if (actionRule.variantPricingEnabled() || actionRule.variantLimitsEnabled()) {
            ResolvedVariantRule variantRule = resolveVariantRule(actionRule.planId(), variantId);
            if (variantRule == null) {
                throw new VariantPricingNotConfiguredException(
                        "No pricing/limit configuration found for variant " + variantId
                                + " on plan " + actionRule.planId() + " for action " + actionCode);
            }
            if (actionRule.variantPricingEnabled()) {
                memberCost = variantRule.memberCost();
                actualCost = variantRule.actualCost();
            }
            if (actionRule.variantLimitsEnabled()) {
                limitValue = variantRule.limitValue();
                periodType = variantRule.periodType();
                applyAfter = variantRule.applyAfter();
                trackerRuleId = variantRule.variantRuleId();
                variantScopedLimit = true;
            }
        }

        LocalDate[] period = resolvePeriod(periodType, actionRule.subPeriodStart(), actionRule.subPeriodEnd());
        LocalDate periodStart = period[0];
        LocalDate periodEnd   = period[1];

        if (limitValue == null) {
            return new VariantCostResult(trackerRuleId, variantScopedLimit, memberCost, true, false, false,
                    periodStart, periodEnd, 0, null, periodType);
        }

        Optional<ActionLimitRepository.TrackerRow> tracker = variantScopedLimit
                ? limitRepo.findByVariant(userId, trackerRuleId, periodStart)
                : limitRepo.find(userId, trackerRuleId, periodStart);
        int usedCount = tracker.map(ActionLimitRepository.TrackerRow::usedCount).orElse(0);

        if (tracker.isEmpty()) {
            Optional<ActionLimitRepository.TrackerRow> latest = variantScopedLimit
                    ? limitRepo.findLatestByVariant(userId, trackerRuleId)
                    : limitRepo.findLatest(userId, trackerRuleId);
            if (latest.isPresent()) {
                ActionLimitRepository.TrackerRow row = latest.get();
                LocalDate today = LocalDate.now();
                if (row.periodEndDate() != null && !today.isAfter(row.periodEndDate())) {
                    usedCount = row.usedCount();
                }
            }
        }

        boolean allowanceAvailable = usedCount < limitValue;

        if (allowanceAvailable) {
            return new VariantCostResult(trackerRuleId, variantScopedLimit, memberCost, true, false, false,
                    periodStart, periodEnd, usedCount, limitValue, periodType);
        }

        if (!applyAfter) {
            return new VariantCostResult(trackerRuleId, variantScopedLimit, 0, false, true, true,
                    periodStart, periodEnd, usedCount, limitValue, periodType);
        }

        return new VariantCostResult(trackerRuleId, variantScopedLimit, actualCost, false, true, false,
                periodStart, periodEnd, usedCount, limitValue, periodType);
    }

    private LocalDate[] resolvePeriod(String periodType, Object subPeriodStart, Object subPeriodEnd) {
        LocalDate today = LocalDate.now();

        return switch (periodType != null ? periodType : "DAY") {
            case "DAY" -> new LocalDate[]{today, today};
            case "MONTH" -> {
                LocalDate start = today.withDayOfMonth(1);
                LocalDate end   = today.withDayOfMonth(today.lengthOfMonth());
                yield new LocalDate[]{start, end};
            }
            case "BILLING_CYCLE" -> {
                if (subPeriodStart != null && subPeriodEnd != null) {
                    LocalDate s = toLocalDate(subPeriodStart);
                    LocalDate e = toLocalDate(subPeriodEnd);
                    if (s != null && e != null) yield new LocalDate[]{s, e};
                }
                // Fallback to calendar month if no billing period available
                LocalDate start = today.withDayOfMonth(1);
                LocalDate end   = today.withDayOfMonth(today.lengthOfMonth());
                yield new LocalDate[]{start, end};
            }
            default -> new LocalDate[]{today, today};
        };
    }

    private LocalDate toLocalDate(Object obj) {
        if (obj instanceof java.sql.Date d) return d.toLocalDate();
        if (obj instanceof java.time.OffsetDateTime odt) return odt.toLocalDate();
        if (obj instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        return null;
    }
}
