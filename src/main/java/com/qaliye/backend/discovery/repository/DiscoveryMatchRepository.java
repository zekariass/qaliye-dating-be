package com.qaliye.backend.discovery.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DiscoveryMatchRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public DiscoveryMatchRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record MatchRow(UUID id, UUID userOneId, UUID userTwoId,
                           String status, OffsetDateTime matchedAt,
                           OffsetDateTime rewindEligibleUntil,
                           OffsetDateTime firstMessageAt) {}

    private static final String INSERT_MATCH = """
            INSERT INTO matches
                (user_one_id, user_two_id,
                 user_one_like_action_id, user_two_like_action_id,
                 created_by_action_id, status,
                 rewind_eligible_until)
            VALUES
                (:userOneId, :userTwoId,
                 :userOneLikeActionId, :userTwoLikeActionId,
                 :createdByActionId, 'ACTIVE',
                 NOW() + (:gracePeriodMinutes || ' minutes')::INTERVAL)
            RETURNING id, user_one_id, user_two_id, status, matched_at,
                      rewind_eligible_until, first_message_at
            """;

    private static final String FIND_ACTIVE_BY_PAIR = """
            SELECT id, user_one_id, user_two_id, status, matched_at,
                   rewind_eligible_until, first_message_at
            FROM matches
            WHERE user_one_id = :userOneId
              AND user_two_id = :userTwoId
              AND status = 'ACTIVE'
            """;

    private static final String END_MATCH = """
            UPDATE matches
            SET status = 'ENDED',
                end_reason = :endReason,
                ended_by_user_id = :endedByUserId,
                ended_at = NOW(),
                updated_at = NOW()
            WHERE id = :matchId
              AND status = 'ACTIVE'
            """;

    public MatchRow insertMatch(UUID userOneId, UUID userTwoId,
                                UUID userOneLikeActionId, UUID userTwoLikeActionId,
                                UUID createdByActionId, int gracePeriodMinutes) {
        var params = new MapSqlParameterSource()
                .addValue("userOneId", userOneId)
                .addValue("userTwoId", userTwoId)
                .addValue("userOneLikeActionId", userOneLikeActionId)
                .addValue("userTwoLikeActionId", userTwoLikeActionId)
                .addValue("createdByActionId", createdByActionId)
                .addValue("gracePeriodMinutes", gracePeriodMinutes);
        MatchRow row = jdbc.query(INSERT_MATCH, params, rs -> {
            if (!rs.next()) return null;
            return mapRow(rs, 0);
        });
        if (row == null) {
            throw new IllegalStateException("INSERT INTO matches returned no row");
        }
        return row;
    }

    public Optional<MatchRow> findActiveByPair(UUID userOneId, UUID userTwoId) {
        var params = new MapSqlParameterSource()
                .addValue("userOneId", userOneId)
                .addValue("userTwoId", userTwoId);
        return jdbc.query(FIND_ACTIVE_BY_PAIR, params, this::mapRow).stream().findFirst();
    }

    public int endMatch(UUID matchId, String endReason, UUID endedByUserId) {
        var params = new MapSqlParameterSource()
                .addValue("matchId", matchId)
                .addValue("endReason", endReason)
                .addValue("endedByUserId", endedByUserId);
        return jdbc.update(END_MATCH, params);
    }

    private MatchRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new MatchRow(
                rs.getObject("id", UUID.class),
                rs.getObject("user_one_id", UUID.class),
                rs.getObject("user_two_id", UUID.class),
                rs.getString("status"),
                rs.getObject("matched_at", OffsetDateTime.class),
                rs.getObject("rewind_eligible_until", OffsetDateTime.class),
                rs.getObject("first_message_at", OffsetDateTime.class)
        );
    }

}
