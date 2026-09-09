package com.qaliye.backend.billing.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tracks per-sender-recipient message counts for subscription_plan_limit_and_cost
 * rules with period_type = 'LIFETIME'.
 *
 * The unique constraint for LIFETIME rows is on (sender_id, recipient_id, rule_id)
 * WHERE period_start_date IS NULL (partial index: umpt_lifetime_unique).
 *
 * Period-based pair tracking (DAY/MONTH/BILLING_CYCLE) is reserved for future use;
 * those period types currently route to user_action_limits_tracker (global per-user).
 */
@Repository
public class MessagePairTrackerRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public MessagePairTrackerRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // =========================================================================
    // LIFETIME tracking  (period_start_date IS NULL)
    // =========================================================================

    private static final String ENSURE_LIFETIME_SQL = """
            INSERT INTO user_message_pair_tracker (sender_id, recipient_id, rule_id)
            VALUES (:senderId, :recipientId, :ruleId)
            ON CONFLICT (sender_id, recipient_id, rule_id) WHERE period_start_date IS NULL
            DO NOTHING
            """;

    /**
     * Ensures a LIFETIME tracker row exists for this (sender, recipient, rule) triple.
     * Safe to call concurrently — ON CONFLICT DO NOTHING.
     */
    public void ensureLifetimePairExists(UUID senderId, UUID recipientId, UUID ruleId) {
        var params = new MapSqlParameterSource()
                .addValue("senderId", senderId)
                .addValue("recipientId", recipientId)
                .addValue("ruleId", ruleId);
        jdbc.update(ENSURE_LIFETIME_SQL, params);
    }

    private static final String INCREMENT_LIFETIME_BY_UNDER_LIMIT_SQL = """
            UPDATE user_message_pair_tracker
            SET message_count = message_count + :count,
                updated_at    = CURRENT_TIMESTAMP
            WHERE sender_id    = :senderId
              AND recipient_id = :recipientId
              AND rule_id      = :ruleId
              AND period_start_date IS NULL
              AND message_count + :count <= :limitValue
            RETURNING message_count
            """;

    /**
     * Atomically increments message_count by {@code count} only when
     * {@code message_count + count <= limitValue}.
     *
     * Returns the new message_count on success, or empty if the limit would be exceeded.
     * Use count = 1 for a single message; use count > 1 for batch attachment sends.
     */
    public Optional<Integer> tryIncrementLifetimeByUnderLimit(UUID senderId, UUID recipientId,
                                                               UUID ruleId, int limitValue, int count) {
        if (count <= 0) return Optional.of(0);
        var params = new MapSqlParameterSource()
                .addValue("senderId", senderId)
                .addValue("recipientId", recipientId)
                .addValue("ruleId", ruleId)
                .addValue("limitValue", limitValue)
                .addValue("count", count);
        List<Integer> rows = jdbc.query(INCREMENT_LIFETIME_BY_UNDER_LIMIT_SQL, params,
                (rs, rn) -> rs.getInt("message_count"));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static final String INCREMENT_LIFETIME_BY_SQL = """
            UPDATE user_message_pair_tracker
            SET message_count = message_count + :count,
                updated_at    = CURRENT_TIMESTAMP
            WHERE sender_id    = :senderId
              AND recipient_id = :recipientId
              AND rule_id      = :ruleId
              AND period_start_date IS NULL
            """;

    /**
     * Unconditionally increments message_count by {@code count}.
     * Used after the free limit is exhausted and credits are being charged.
     */
    public void incrementLifetimeBy(UUID senderId, UUID recipientId, UUID ruleId, int count) {
        if (count <= 0) return;
        var params = new MapSqlParameterSource()
                .addValue("senderId", senderId)
                .addValue("recipientId", recipientId)
                .addValue("ruleId", ruleId)
                .addValue("count", count);
        jdbc.update(INCREMENT_LIFETIME_BY_SQL, params);
    }
}
