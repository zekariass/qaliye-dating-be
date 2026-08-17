package com.qaliye.backend.billing.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages the user_action_limits_tracker table, which tracks usage of subscription-based
 * action allowances per user, per plan rule, per period.
 *
 * Replaces the old user_daily_limits and user_quota_usage tables.
 */
@Repository
public class ActionLimitRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ActionLimitRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record TrackerRow(
            UUID id,
            UUID userId,
            UUID subscriptionPlanLimitAndCostId,
            int usedCount,
            LocalDate periodStartDate,
            LocalDate periodEndDate
    ) {}

    private static final String FIND_ACTIVE_TRACKER_SQL = """
            SELECT id, user_id, subscription_plan_limit_and_cost_id,
                   used_count, period_start_date, period_end_date
            FROM user_action_limits_tracker
            WHERE user_id = :userId
              AND subscription_plan_limit_and_cost_id = :ruleId
              AND period_start_date = :periodStart
            """;

    private static final String FIND_LATEST_TRACKER_SQL = """
            SELECT id, user_id, subscription_plan_limit_and_cost_id,
                   used_count, period_start_date, period_end_date
            FROM user_action_limits_tracker
            WHERE user_id = :userId
              AND subscription_plan_limit_and_cost_id = :ruleId
            ORDER BY period_start_date DESC
            LIMIT 1
            """;

    private static final String FIND_ACTIVE_TRACKER_FOR_UPDATE_SQL = """
            SELECT id, user_id, subscription_plan_limit_and_cost_id,
                   used_count, period_start_date, period_end_date
            FROM user_action_limits_tracker
            WHERE user_id = :userId
              AND subscription_plan_limit_and_cost_id = :ruleId
              AND period_start_date = :periodStart
            FOR UPDATE
            """;

    private static final String FIND_LATEST_TRACKER_FOR_UPDATE_SQL = """
            SELECT id, user_id, subscription_plan_limit_and_cost_id,
                   used_count, period_start_date, period_end_date
            FROM user_action_limits_tracker
            WHERE user_id = :userId
              AND subscription_plan_limit_and_cost_id = :ruleId
            ORDER BY period_start_date DESC
            LIMIT 1
            FOR UPDATE
            """;

    private static final String INSERT_TRACKER_SQL = """
            INSERT INTO user_action_limits_tracker
                (user_id, subscription_plan_limit_and_cost_id, used_count,
                 period_start_date, period_end_date)
            VALUES
                (:userId, :ruleId, 0, :periodStart, :periodEnd)
            ON CONFLICT (user_id, subscription_plan_limit_and_cost_id, period_start_date)
                DO NOTHING
            RETURNING id, user_id, subscription_plan_limit_and_cost_id,
                      used_count, period_start_date, period_end_date
            """;

    private static final String INCREMENT_USED_COUNT_SQL = """
            UPDATE user_action_limits_tracker
            SET used_count = used_count + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            RETURNING used_count
            """;

    private static final String RESET_TRACKER_SQL = """
            UPDATE user_action_limits_tracker
            SET used_count = 0,
                period_start_date = :periodStart,
                period_end_date = :periodEnd,
                updated_at = CURRENT_TIMESTAMP
            WHERE user_id = :userId
              AND subscription_plan_limit_and_cost_id = :ruleId
            """;

    public Optional<TrackerRow> findForUpdate(UUID userId, UUID ruleId, LocalDate periodStart) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("ruleId", ruleId)
                .addValue("periodStart", periodStart);
        return jdbc.query(FIND_ACTIVE_TRACKER_FOR_UPDATE_SQL, params, this::mapRow)
                .stream().findFirst();
    }

    public Optional<TrackerRow> find(UUID userId, UUID ruleId, LocalDate periodStart) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("ruleId", ruleId)
                .addValue("periodStart", periodStart);
        return jdbc.query(FIND_ACTIVE_TRACKER_SQL, params, this::mapRow)
                .stream().findFirst();
    }

    public Optional<TrackerRow> findLatest(UUID userId, UUID ruleId) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("ruleId", ruleId);
        return jdbc.query(FIND_LATEST_TRACKER_SQL, params, this::mapRow)
                .stream().findFirst();
    }

    public Optional<TrackerRow> findLatestForUpdate(UUID userId, UUID ruleId) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("ruleId", ruleId);
        return jdbc.query(FIND_LATEST_TRACKER_FOR_UPDATE_SQL, params, this::mapRow)
                .stream().findFirst();
    }

    public Optional<TrackerRow> ensureExists(UUID userId, UUID ruleId,
                                             LocalDate periodStart, LocalDate periodEnd) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("ruleId", ruleId)
                .addValue("periodStart", periodStart)
                .addValue("periodEnd", periodEnd);
        List<TrackerRow> rows = jdbc.query(INSERT_TRACKER_SQL, params, this::mapRow);
        if (!rows.isEmpty()) return Optional.of(rows.get(0));
        return find(userId, ruleId, periodStart);
    }

    /**
     * Atomically increments used_count only if {@code used_count < limitValue}.
     * Returns the new used_count on success, or empty if the limit was already reached.
     * Safe for concurrent calls — no separate read needed before calling this.
     */
    public Optional<Integer> tryIncrementUnderLimit(UUID userId, UUID ruleId,
                                                    LocalDate periodStart, int limitValue) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("ruleId", ruleId)
                .addValue("periodStart", periodStart)
                .addValue("limitValue", limitValue);
        List<Integer> rows = jdbc.query("""
                UPDATE user_action_limits_tracker
                SET used_count = used_count + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE user_id = :userId
                  AND subscription_plan_limit_and_cost_id = :ruleId
                  AND period_start_date = :periodStart
                  AND used_count < :limitValue
                RETURNING used_count
                """, params, (rs, rn) -> rs.getInt("used_count"));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Atomically increments used_count by {@code count} only if
     * {@code used_count + count <= limitValue}.
     * Returns the new used_count on success, or empty if the limit would be exceeded.
     */
    public Optional<Integer> tryIncrementByUnderLimit(UUID userId, UUID ruleId,
                                                      LocalDate periodStart, int limitValue, int count) {
        if (count <= 0) return Optional.of(0);
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("ruleId", ruleId)
                .addValue("periodStart", periodStart)
                .addValue("limitValue", limitValue)
                .addValue("count", count);
        List<Integer> rows = jdbc.query("""
                UPDATE user_action_limits_tracker
                SET used_count = used_count + :count,
                    updated_at = CURRENT_TIMESTAMP
                WHERE user_id = :userId
                  AND subscription_plan_limit_and_cost_id = :ruleId
                  AND period_start_date = :periodStart
                  AND used_count + :count <= :limitValue
                RETURNING used_count
                """, params, (rs, rn) -> rs.getInt("used_count"));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public int increment(UUID trackerId) {
        var params = new MapSqlParameterSource("id", trackerId);
        Integer newCount = jdbc.queryForObject(INCREMENT_USED_COUNT_SQL, params, Integer.class);
        return newCount != null ? newCount : 0;
    }

    public int incrementBy(UUID trackerId, int count) {
        if (count <= 0) return 0;
        var params = new MapSqlParameterSource()
                .addValue("id", trackerId)
                .addValue("count", count);
        Integer newCount = jdbc.queryForObject("""
                UPDATE user_action_limits_tracker
                SET used_count = used_count + :count,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                RETURNING used_count
                """, params, Integer.class);
        return newCount != null ? newCount : 0;
    }

    public void resetForNewPeriod(UUID userId, UUID ruleId, LocalDate periodStart, LocalDate periodEnd) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("ruleId", ruleId)
                .addValue("periodStart", periodStart)
                .addValue("periodEnd", periodEnd);
        jdbc.update(RESET_TRACKER_SQL, params);
    }

    private TrackerRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Object startObj = rs.getObject("period_start_date");
        Object endObj   = rs.getObject("period_end_date");
        return new TrackerRow(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("subscription_plan_limit_and_cost_id", UUID.class),
                rs.getInt("used_count"),
                startObj instanceof java.sql.Date d ? d.toLocalDate()
                        : (startObj != null ? LocalDate.parse(startObj.toString()) : null),
                endObj instanceof java.sql.Date d ? d.toLocalDate()
                        : (endObj != null ? LocalDate.parse(endObj.toString()) : null)
        );
    }
}
