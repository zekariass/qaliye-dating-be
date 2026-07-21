package com.qaliye.backend.user;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
class AuthAnonymizationTaskRepository {

    static final int MAX_ATTEMPTS = 10;

    private final NamedParameterJdbcTemplate jdbc;

    AuthAnonymizationTaskRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insertPendingIfAbsent(UUID userId) {
        jdbc.update("""
                INSERT INTO auth_anonymization_tasks (user_id)
                VALUES (:userId)
                ON CONFLICT (user_id) DO NOTHING
                """, new MapSqlParameterSource("userId", userId));
    }

    void markCompleted(UUID userId) {
        jdbc.update("""
                UPDATE auth_anonymization_tasks
                SET status     = 'COMPLETED',
                    updated_at = NOW()
                WHERE user_id = :userId
                """, new MapSqlParameterSource("userId", userId));
    }

    void scheduleRetry(UUID userId, String error) {
        jdbc.update("""
                UPDATE auth_anonymization_tasks
                SET attempts      = attempts + 1,
                    last_error    = :error,
                    next_retry_at = NOW() + (INTERVAL '1 minute' * (POWER(2, LEAST(attempts, 9))::int)),
                    status        = CASE WHEN attempts + 1 >= :maxAttempts
                                         THEN 'FAILED_PERMANENT'
                                         ELSE 'PENDING' END,
                    updated_at    = NOW()
                WHERE user_id = :userId
                """, new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("error", error)
                        .addValue("maxAttempts", MAX_ATTEMPTS));
    }

    List<UUID> claimPending(int limit) {
        return jdbc.query("""
                SELECT user_id
                FROM auth_anonymization_tasks
                WHERE status = 'PENDING'
                  AND next_retry_at <= NOW()
                ORDER BY next_retry_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """, new MapSqlParameterSource("limit", limit),
                (rs, n) -> rs.getObject("user_id", UUID.class));
    }
}
