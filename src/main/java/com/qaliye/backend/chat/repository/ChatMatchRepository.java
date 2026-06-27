package com.qaliye.backend.chat.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ChatMatchRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ChatMatchRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record MatchRow(
            UUID id, UUID userOneId, UUID userTwoId, String status,
            OffsetDateTime matchedAt, OffsetDateTime endedAt, String endReason,
            long nextMessageSequence,
            long userOneLastDeliveredSequence, long userTwoLastDeliveredSequence,
            long userOneLastReadSequence, long userTwoLastReadSequence,
            OffsetDateTime userOneLastDeliveredAt, OffsetDateTime userTwoLastDeliveredAt,
            OffsetDateTime userOneLastReadAt, OffsetDateTime userTwoLastReadAt,
            OffsetDateTime firstMessageAt, OffsetDateTime lastMessageAt
    ) {
        public boolean isParticipant(UUID userId) {
            return userOneId.equals(userId) || userTwoId.equals(userId);
        }
        public boolean isUserOne(UUID userId) {
            return userOneId.equals(userId);
        }
        public UUID otherUserId(UUID callerId) {
            return userOneId.equals(callerId) ? userTwoId : userOneId;
        }
    }

    private static final String SELECT_COLS = """
            m.id, m.user_one_id, m.user_two_id, m.status,
            m.matched_at, m.ended_at, m.end_reason,
            m.next_message_sequence,
            m.user_one_last_delivered_sequence, m.user_two_last_delivered_sequence,
            m.user_one_last_read_sequence, m.user_two_last_read_sequence,
            m.user_one_last_delivered_at, m.user_two_last_delivered_at,
            m.user_one_last_read_at, m.user_two_last_read_at,
            m.first_message_at, m.last_message_at
            """;

    private static final String FIND_BY_ID_SQL =
            "SELECT " + SELECT_COLS + " FROM matches m WHERE m.id = :matchId";

    private static final String FIND_BY_ID_FOR_UPDATE_SQL =
            "SELECT " + SELECT_COLS + " FROM matches m WHERE m.id = :matchId FOR UPDATE";

    private static final String END_MATCH_SQL = """
            UPDATE matches
            SET status           = 'ENDED',
                end_reason       = :endReason,
                ended_by_user_id = :endedByUserId,
                ended_at         = NOW(),
                updated_at       = NOW()
            WHERE id = :matchId
              AND status = 'ACTIVE'
            RETURNING id
            """;

    private static final String INCREMENT_SEQUENCE_SQL = """
            UPDATE matches
            SET next_message_sequence = next_message_sequence + 1,
                updated_at = NOW()
            WHERE id = :matchId
            RETURNING next_message_sequence - 1 AS reserved_sequence
            """;

    private static final String UPDATE_DELIVERED_SEQUENCE_USER_ONE_SQL = """
            UPDATE matches
            SET user_one_last_delivered_sequence = :seq,
                user_one_last_delivered_at       = NOW(),
                updated_at                       = NOW()
            WHERE id = :matchId
              AND user_one_last_delivered_sequence < :seq
            """;

    private static final String UPDATE_DELIVERED_SEQUENCE_USER_TWO_SQL = """
            UPDATE matches
            SET user_two_last_delivered_sequence = :seq,
                user_two_last_delivered_at       = NOW(),
                updated_at                       = NOW()
            WHERE id = :matchId
              AND user_two_last_delivered_sequence < :seq
            """;

    private static final String UPDATE_READ_SEQUENCE_USER_ONE_SQL = """
            UPDATE matches
            SET user_one_last_read_sequence      = :readSeq,
                user_one_last_read_at            = NOW(),
                user_one_last_delivered_sequence = GREATEST(user_one_last_delivered_sequence, :deliveredSeq),
                user_one_last_delivered_at       = CASE
                    WHEN user_one_last_delivered_sequence < :deliveredSeq THEN NOW()
                    ELSE user_one_last_delivered_at
                END,
                updated_at = NOW()
            WHERE id = :matchId
              AND user_one_last_read_sequence < :readSeq
            """;

    private static final String UPDATE_READ_SEQUENCE_USER_TWO_SQL = """
            UPDATE matches
            SET user_two_last_read_sequence      = :readSeq,
                user_two_last_read_at            = NOW(),
                user_two_last_delivered_sequence = GREATEST(user_two_last_delivered_sequence, :deliveredSeq),
                user_two_last_delivered_at       = CASE
                    WHEN user_two_last_delivered_sequence < :deliveredSeq THEN NOW()
                    ELSE user_two_last_delivered_at
                END,
                updated_at = NOW()
            WHERE id = :matchId
              AND user_two_last_read_sequence < :readSeq
            """;

    private static final String ACTIVE_MATCH_BY_PAIR_SQL =
            "SELECT " + SELECT_COLS + """
            FROM matches m
            WHERE (
                (m.user_one_id = :userOneId AND m.user_two_id = :userTwoId)
                OR (m.user_one_id = :userTwoId AND m.user_two_id = :userOneId)
            )
            AND m.status = 'ACTIVE'
            FOR UPDATE
            """;

    public Optional<MatchRow> findById(UUID matchId) {
        var params = new MapSqlParameterSource("matchId", matchId);
        List<MatchRow> rows = jdbc.query(FIND_BY_ID_SQL, params, this::mapRow);
        return rows.stream().findFirst();
    }

    public Optional<MatchRow> findByIdForUpdate(UUID matchId) {
        var params = new MapSqlParameterSource("matchId", matchId);
        List<MatchRow> rows = jdbc.query(FIND_BY_ID_FOR_UPDATE_SQL, params, this::mapRow);
        return rows.stream().findFirst();
    }

    public Optional<MatchRow> findActiveByPairForUpdate(UUID userOneId, UUID userTwoId) {
        var params = new MapSqlParameterSource()
                .addValue("userOneId", userOneId)
                .addValue("userTwoId", userTwoId);
        List<MatchRow> rows = jdbc.query(ACTIVE_MATCH_BY_PAIR_SQL, params, this::mapRow);
        return rows.stream().findFirst();
    }

    public Optional<UUID> endMatch(UUID matchId, String endReason, UUID endedByUserId) {
        var params = new MapSqlParameterSource()
                .addValue("matchId", matchId)
                .addValue("endReason", endReason)
                .addValue("endedByUserId", endedByUserId);
        List<UUID> ids = jdbc.query(END_MATCH_SQL, params,
                (rs, n) -> rs.getObject("id", UUID.class));
        return ids.stream().findFirst();
    }

    public long reserveAndIncrementSequence(UUID matchId) {
        var params = new MapSqlParameterSource("matchId", matchId);
        Long seq = jdbc.queryForObject(INCREMENT_SEQUENCE_SQL, params, (rs, n) -> rs.getLong("reserved_sequence"));
        if (seq == null) throw new IllegalStateException("Failed to reserve sequence for match " + matchId);
        return seq;
    }

    public int updateDeliveredSequence(UUID matchId, boolean isUserOne, long seq) {
        var params = new MapSqlParameterSource()
                .addValue("matchId", matchId)
                .addValue("seq", seq);
        String sql = isUserOne ? UPDATE_DELIVERED_SEQUENCE_USER_ONE_SQL : UPDATE_DELIVERED_SEQUENCE_USER_TWO_SQL;
        return jdbc.update(sql, params);
    }

    public int updateReadSequence(UUID matchId, boolean isUserOne, long readSeq, long deliveredSeq) {
        var params = new MapSqlParameterSource()
                .addValue("matchId", matchId)
                .addValue("readSeq", readSeq)
                .addValue("deliveredSeq", deliveredSeq);
        String sql = isUserOne ? UPDATE_READ_SEQUENCE_USER_ONE_SQL : UPDATE_READ_SEQUENCE_USER_TWO_SQL;
        return jdbc.update(sql, params);
    }

    private MatchRow mapRow(ResultSet rs, int n) throws SQLException {
        return new MatchRow(
                rs.getObject("id", UUID.class),
                rs.getObject("user_one_id", UUID.class),
                rs.getObject("user_two_id", UUID.class),
                rs.getString("status"),
                rs.getObject("matched_at", OffsetDateTime.class),
                rs.getObject("ended_at", OffsetDateTime.class),
                rs.getString("end_reason"),
                rs.getLong("next_message_sequence"),
                rs.getLong("user_one_last_delivered_sequence"),
                rs.getLong("user_two_last_delivered_sequence"),
                rs.getLong("user_one_last_read_sequence"),
                rs.getLong("user_two_last_read_sequence"),
                rs.getObject("user_one_last_delivered_at", OffsetDateTime.class),
                rs.getObject("user_two_last_delivered_at", OffsetDateTime.class),
                rs.getObject("user_one_last_read_at", OffsetDateTime.class),
                rs.getObject("user_two_last_read_at", OffsetDateTime.class),
                rs.getObject("first_message_at", OffsetDateTime.class),
                rs.getObject("last_message_at", OffsetDateTime.class)
        );
    }
}
