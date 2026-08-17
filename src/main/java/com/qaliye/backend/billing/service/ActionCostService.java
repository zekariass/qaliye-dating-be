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

    public ActionCostResult evaluate(UUID userId, String actionCode) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("actionCode", actionCode);

        List<ActionCostResult> results = jdbc.query(RESOLVE_PLAN_RULE_SQL, params, (rs, rn) -> {
            UUID ruleId             = rs.getObject("rule_id", UUID.class);
            long memberCost         = rs.getLong("member_credit_cost");
            long actualCost         = rs.getLong("actual_credit_cost");
            Object limitValObj      = rs.getObject("limit_value");
            Integer limitValue      = limitValObj != null ? ((Number) limitValObj).intValue() : null;
            String periodType       = rs.getString("period_type");
            boolean applyAfter      = rs.getBoolean("apply_credit_after_limit");
            Object subPeriodStart   = rs.getObject("current_period_start");
            Object subPeriodEnd     = rs.getObject("current_period_end");

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
