package com.qaliye.backend.discovery.service;

import com.qaliye.backend.discovery.dto.UserPlanEntitlement;
import com.qaliye.backend.discovery.repository.EntitlementLedgerRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PlanEntitlementService {

    private final NamedParameterJdbcTemplate jdbc;
    private final EntitlementLedgerRepository ledgerRepo;

    public PlanEntitlementService(NamedParameterJdbcTemplate jdbc,
                                  EntitlementLedgerRepository ledgerRepo) {
        this.jdbc = jdbc;
        this.ledgerRepo = ledgerRepo;
    }

    private static final String RESOLVE_LIMITS_SQL = """
            WITH paid_limits AS (
                SELECT sp.plan_code, sp.plan_kind, spl.limit_type, spl.limit_value
                FROM user_subscriptions us
                JOIN subscription_plans sp  ON sp.id  = us.plan_id
                JOIN subscription_plan_limits spl ON spl.plan_id = sp.id
                WHERE us.user_id = :userId
                  AND us.status IN ('ACTIVE', 'PENDING_VERIFICATION')
                  AND sp.is_active = TRUE
            ),
            free_limits AS (
                SELECT sp.plan_code, sp.plan_kind, spl.limit_type, spl.limit_value
                FROM subscription_plans sp
                JOIN subscription_plan_limits spl ON spl.plan_id = sp.id
                WHERE sp.plan_kind = 'FREE'
                  AND sp.is_active = TRUE
                ORDER BY CASE WHEN sp.country_code = :countryCode THEN 0 ELSE 1 END
                LIMIT 3
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
            WHERE limit_type IN ('DAILY_LIKES', 'DAILY_SUPERLIKES', 'DAILY_REWINDS')
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

        for (Map<String, Object> row : rows) {
            String limitType = (String) row.get("limit_type");
            Object limitValueObj = row.get("limit_value");
            Integer limitValue = limitValueObj != null ? ((Number) limitValueObj).intValue() : null;
            planCode = (String) row.get("plan_code");
            isPaid = "PAID".equals(row.get("plan_kind"));
            switch (limitType) {
                case "DAILY_LIKES" -> dailyLikesLimit = limitValue;
                case "DAILY_SUPERLIKES" -> dailySuperLikesLimit = limitValue;
                case "DAILY_REWINDS" -> dailyRewindsLimit = limitValue;
            }
        }

        int superLikeCredits = ledgerRepo.getBalance(userId, "SUPERLIKE_CREDIT");
        int rewindCredits = ledgerRepo.getBalance(userId, "REWIND_CREDIT");

        return new UserPlanEntitlement(userId, planCode, isPaid,
                dailyLikesLimit, dailySuperLikesLimit, dailyRewindsLimit,
                superLikeCredits, rewindCredits);
    }
}
