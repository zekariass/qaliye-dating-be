package com.qaliye.backend.discovery.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DailyLimitRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public DailyLimitRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record DailyLimitRow(UUID userId, int likesUsed, int superLikesUsed, int rewindsUsed) {}

    private static final String UPSERT_FOR_TODAY = """
            INSERT INTO user_daily_limits (user_id, limit_date, likes_used, super_likes_used, rewinds_used)
            VALUES (:userId, (NOW() AT TIME ZONE 'UTC')::DATE, 0, 0, 0)
            ON CONFLICT (user_id, limit_date) DO NOTHING
            """;

    private static final String SELECT_FOR_UPDATE = """
            SELECT user_id, likes_used, super_likes_used, rewinds_used
            FROM user_daily_limits
            WHERE user_id = :userId
              AND limit_date = (NOW() AT TIME ZONE 'UTC')::DATE
            FOR UPDATE
            """;

    private static final String INCREMENT_LIKES = """
            UPDATE user_daily_limits
            SET likes_used = likes_used + 1
            WHERE user_id = :userId
              AND limit_date = (NOW() AT TIME ZONE 'UTC')::DATE
            """;

    private static final String INCREMENT_SUPERLIKES = """
            UPDATE user_daily_limits
            SET super_likes_used = super_likes_used + 1
            WHERE user_id = :userId
              AND limit_date = (NOW() AT TIME ZONE 'UTC')::DATE
            """;

    private static final String INCREMENT_REWINDS = """
            UPDATE user_daily_limits
            SET rewinds_used = rewinds_used + 1
            WHERE user_id = :userId
              AND limit_date = (NOW() AT TIME ZONE 'UTC')::DATE
            """;

    public void ensureRowExists(UUID userId) {
        jdbc.update(UPSERT_FOR_TODAY, new MapSqlParameterSource("userId", userId));
    }

    public Optional<DailyLimitRow> lockForUpdate(UUID userId) {
        var params = new MapSqlParameterSource("userId", userId);
        return jdbc.query(SELECT_FOR_UPDATE, params, this::mapRow).stream().findFirst();
    }

    public void incrementLikes(UUID userId) {
        jdbc.update(INCREMENT_LIKES, new MapSqlParameterSource("userId", userId));
    }

    public void incrementSuperLikes(UUID userId) {
        jdbc.update(INCREMENT_SUPERLIKES, new MapSqlParameterSource("userId", userId));
    }

    public void incrementRewinds(UUID userId) {
        jdbc.update(INCREMENT_REWINDS, new MapSqlParameterSource("userId", userId));
    }

    private DailyLimitRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new DailyLimitRow(
                rs.getObject("user_id", UUID.class),
                rs.getInt("likes_used"),
                rs.getInt("super_likes_used"),
                rs.getInt("rewinds_used")
        );
    }
}
