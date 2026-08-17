package com.qaliye.backend.actions;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DailyLimitsService {

    /**
     * Resolves the effective plan_id in priority order:
     * 1. User's active, current PAID subscription
     * 2. Active FREE plan for the user's address country_code
     * 3. Active GLOBAL FREE plan (country_code = 'GLOBAL')
     */
    private static final String EFFECTIVE_PLAN_SQL = """
            SELECT fa.code AS limit_type, splac.limit_value
            FROM subscription_plan_limit_and_cost splac
            JOIN feature_actions fa ON fa.id = splac.feature_action_id
            WHERE splac.subscription_plan_id = (
                SELECT us.plan_id
                FROM user_subscriptions us
                WHERE us.user_id = :userId
                  AND us.status = 'ACTIVE'
                  AND us.current_period_end > NOW()
                ORDER BY us.current_period_start DESC
                LIMIT 1
            )
            AND fa.code IN ('LIKE', 'SUPER_LIKE', 'REWIND')
            UNION ALL
            SELECT fa2.code AS limit_type, splac2.limit_value
            FROM subscription_plan_limit_and_cost splac2
            JOIN feature_actions fa2 ON fa2.id = splac2.feature_action_id
            WHERE splac2.subscription_plan_id = (
                SELECT sp.id
                FROM subscription_plans sp
                WHERE sp.plan_kind = 'FREE'
                  AND sp.is_active = TRUE
                  AND sp.country_code = (
                      SELECT a.country_code
                      FROM app_users au
                      JOIN addresses a ON a.id = au.address_id
                      WHERE au.id = :userId
                  )
                LIMIT 1
            )
            AND fa2.code IN ('LIKE', 'SUPER_LIKE', 'REWIND')
              AND NOT EXISTS (
                  SELECT 1 FROM user_subscriptions us2
                  WHERE us2.user_id = :userId
                    AND us2.status = 'ACTIVE'
                    AND us2.current_period_end > NOW()
              )
            UNION ALL
            SELECT fa3.code AS limit_type, splac3.limit_value
            FROM subscription_plan_limit_and_cost splac3
            JOIN feature_actions fa3 ON fa3.id = splac3.feature_action_id
            WHERE splac3.subscription_plan_id = (
                SELECT sp.id FROM subscription_plans sp
                WHERE sp.plan_kind = 'FREE'
                  AND sp.country_code = 'GLOBAL'
                  AND sp.is_active = TRUE
                LIMIT 1
            )
            AND fa3.code IN ('LIKE', 'SUPER_LIKE', 'REWIND')
              AND NOT EXISTS (
                  SELECT 1 FROM user_subscriptions us3
                  WHERE us3.user_id = :userId
                    AND us3.status = 'ACTIVE'
                    AND us3.current_period_end > NOW()
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM subscription_plans sp2
                  WHERE sp2.plan_kind = 'FREE'
                    AND sp2.is_active = TRUE
                    AND sp2.country_code = (
                        SELECT a.country_code
                        FROM app_users au
                        JOIN addresses a ON a.id = au.address_id
                        WHERE au.id = :userId
                    )
              )
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public DailyLimitsService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns the effective plan limits. A NULL limit_value in a row means unlimited
     * for that action type (represented as Integer.MAX_VALUE here).
     */
    @Cacheable(value = "subscriptionFeatures", key = "#userId")
    public TierLimits getTierLimits(UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                EFFECTIVE_PLAN_SQL, new MapSqlParameterSource("userId", userId));

        if (rows.isEmpty()) {
            return TierLimits.free();
        }

        int likes = TierLimits.free().likesPerDay();
        int superLikes = TierLimits.free().superLikesPerDay();
        int rewinds = TierLimits.free().rewindsPerDay();

        for (Map<String, Object> row : rows) {
            String limitType = (String) row.get("limit_type");
            Object limitValueObj = row.get("limit_value");
            int limitValue = limitValueObj == null ? Integer.MAX_VALUE : ((Number) limitValueObj).intValue();
            switch (limitType) {
                case "LIKE"      -> likes = limitValue;
                case "SUPER_LIKE" -> superLikes = limitValue;
                case "REWIND"    -> rewinds = limitValue;
            }
        }
        return new TierLimits(likes, superLikes, rewinds);
    }
}
