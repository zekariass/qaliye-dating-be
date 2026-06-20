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
            SELECT spl.limit_type, spl.limit_value
            FROM subscription_plan_limits spl
            WHERE spl.plan_id = (
                SELECT us.plan_id
                FROM user_subscriptions us
                WHERE us.user_id = :userId
                  AND us.status = 'ACTIVE'
                  AND us.current_period_end > NOW()
                ORDER BY us.current_period_start DESC
                LIMIT 1
            )
            UNION ALL
            SELECT spl2.limit_type, spl2.limit_value
            FROM subscription_plan_limits spl2
            WHERE spl2.plan_id = (
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
              AND NOT EXISTS (
                  SELECT 1 FROM user_subscriptions us2
                  WHERE us2.user_id = :userId
                    AND us2.status = 'ACTIVE'
                    AND us2.current_period_end > NOW()
              )
            UNION ALL
            SELECT spl3.limit_type, spl3.limit_value
            FROM subscription_plan_limits spl3
            WHERE spl3.plan_id = (
                SELECT sp.id FROM subscription_plans sp
                WHERE sp.plan_kind = 'FREE'
                  AND sp.country_code = 'GLOBAL'
                  AND sp.is_active = TRUE
                LIMIT 1
            )
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
                case "DAILY_LIKES" -> likes = limitValue;
                case "DAILY_SUPERLIKES" -> superLikes = limitValue;
                case "DAILY_REWINDS" -> rewinds = limitValue;
            }
        }
        return new TierLimits(likes, superLikes, rewinds);
    }
}
