package com.qaliye.backend.discovery.service;

import com.qaliye.backend.discovery.dto.UserPlanEntitlement;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PlanEntitlementService {

    private final NamedParameterJdbcTemplate jdbc;

    public PlanEntitlementService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String RESOLVE_LIMITS_SQL = """
            WITH paid_limits AS (
                SELECT sp.plan_code, sp.plan_kind, fa.code AS limit_type, splac.limit_value
                FROM user_subscriptions us
                JOIN subscription_plans sp     ON sp.id    = us.plan_id
                JOIN subscription_plan_limit_and_cost splac ON splac.subscription_plan_id = sp.id
                JOIN feature_actions fa        ON fa.id    = splac.feature_action_id
                WHERE us.user_id = :userId
                  AND us.status IN ('ACTIVE', 'PENDING_VERIFICATION')
                  AND sp.is_active = TRUE
                  AND fa.code IN ('LIKE', 'SUPER_LIKE', 'REWIND', 'VOICE_MESSAGE', 'IMAGE_MESSAGE')
            ),
            free_limits AS (
                SELECT DISTINCT ON (fa.code)
                    sp.plan_code, sp.plan_kind, fa.code AS limit_type, splac.limit_value
                FROM subscription_plans sp
                JOIN subscription_plan_limit_and_cost splac ON splac.subscription_plan_id = sp.id
                JOIN feature_actions fa ON fa.id = splac.feature_action_id
                WHERE sp.plan_kind = 'FREE'
                  AND sp.is_active = TRUE
                  AND fa.code IN ('LIKE', 'SUPER_LIKE', 'REWIND', 'VOICE_MESSAGE', 'IMAGE_MESSAGE')
                ORDER BY fa.code, CASE WHEN sp.country_code = :countryCode THEN 0 ELSE 1 END
            ),
            resolved AS (
                SELECT plan_code, plan_kind, limit_type, limit_value FROM paid_limits
                UNION ALL
                SELECT fl.plan_code, fl.plan_kind, fl.limit_type, fl.limit_value
                FROM free_limits fl
                WHERE fl.limit_type NOT IN (SELECT limit_type FROM paid_limits)
            )
            SELECT plan_code, plan_kind, limit_type, limit_value
            FROM resolved
            """;

    private static final String GET_USER_COUNTRY_SQL = """
            SELECT COALESCE(a.country_code, 'GLOBAL')
            FROM app_users au
            LEFT JOIN addresses a ON a.id = au.address_id
            WHERE au.id = :userId
            """;

    public UserPlanEntitlement loadEntitlement(UUID userId) {
        String countryCode = jdbc.queryForObject(GET_USER_COUNTRY_SQL,
                new MapSqlParameterSource("userId", userId), String.class);
        if (countryCode == null) countryCode = "GLOBAL";

        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("countryCode", countryCode);

        List<Map<String, Object>> rows = jdbc.queryForList(RESOLVE_LIMITS_SQL, params);

        String planCode = "FREE";
        boolean isPaid = false;
        Integer dailyLikesLimit = 50;
        Integer dailySuperLikesLimit = 1;
        Integer dailyRewindsLimit = 1;
        Integer dailyVoiceChatMsgLimit = 0;
        Integer dailyImageChatMsgLimit = 0;

        for (Map<String, Object> row : rows) {
            String limitType = (String) row.get("limit_type");
            Object limitValueObj = row.get("limit_value");
            Integer limitValue = limitValueObj != null ? ((Number) limitValueObj).intValue() : null;
            planCode = (String) row.get("plan_code");
            isPaid = "PAID".equals(row.get("plan_kind"));
            switch (limitType) {
                case "LIKE"          -> dailyLikesLimit = limitValue;
                case "SUPER_LIKE"    -> dailySuperLikesLimit = limitValue;
                case "REWIND"        -> dailyRewindsLimit = limitValue;
                case "VOICE_MESSAGE" -> dailyVoiceChatMsgLimit = limitValue;
                case "IMAGE_MESSAGE" -> dailyImageChatMsgLimit = limitValue;
            }
        }

        return new UserPlanEntitlement(userId, planCode, isPaid,
                dailyLikesLimit, dailySuperLikesLimit, dailyRewindsLimit,
                dailyVoiceChatMsgLimit, dailyImageChatMsgLimit,
                0, 0);
    }
}
