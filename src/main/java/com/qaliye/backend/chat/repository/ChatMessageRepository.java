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
public class ChatMessageRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ChatMessageRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record MessageRow(
            UUID id, UUID matchId, long sequenceNumber, UUID senderUserId,
            String messageType, String body, String moderationStatus,
            OffsetDateTime createdAt, OffsetDateTime deletedAt
    ) {}

    private static final String SELECT_COLS = """
            id, match_id, sequence_number, sender_user_id,
            message_type, body, moderation_status, created_at, deleted_at
            """;

    private static final String FIND_BY_IDEMPOTENCY_SQL =
            "SELECT " + SELECT_COLS + """
            FROM messages
            WHERE sender_user_id = :senderUserId
              AND client_message_id = :clientMessageId
            """;

    private static final String INSERT_MESSAGE_SQL = """
            INSERT INTO messages
                (match_id, sender_user_id, client_message_id, message_type,
                 body, moderation_status, sequence_number, created_at, updated_at)
            VALUES
                (:matchId, :senderUserId, :clientMessageId, :messageType,
                 :body, 'APPROVED', :sequenceNumber, clock_timestamp(), clock_timestamp())
            """ + "RETURNING " + SELECT_COLS;

    private static final String GET_MESSAGES_BEFORE_SQL =
            "SELECT " + SELECT_COLS + """
            FROM messages
            WHERE match_id = :matchId
              AND sequence_number < :beforeSequence
              AND deleted_at IS NULL
              AND moderation_status = 'APPROVED'
            ORDER BY sequence_number ASC
            LIMIT :limit
            """;

    private static final String GET_MESSAGES_AFTER_SQL =
            "SELECT " + SELECT_COLS + """
            FROM messages
            WHERE match_id = :matchId
              AND sequence_number > :afterSequence
              AND deleted_at IS NULL
              AND moderation_status = 'APPROVED'
            ORDER BY sequence_number ASC
            LIMIT :limit
            """;

    private static final String GET_LATEST_MESSAGES_SQL =
            "SELECT " + SELECT_COLS + """
            FROM messages
            WHERE match_id = :matchId
              AND deleted_at IS NULL
              AND moderation_status = 'APPROVED'
            ORDER BY sequence_number DESC
            LIMIT :limit
            """;

    private static final String GET_LAST_MESSAGE_SQL =
            "SELECT " + SELECT_COLS + """
            FROM messages
            WHERE match_id = :matchId
              AND deleted_at IS NULL
              AND moderation_status = 'APPROVED'
            ORDER BY sequence_number DESC
            LIMIT 1
            """;

    private static final String COUNT_UNREAD_SQL = """
            SELECT COUNT(*) FROM messages
            WHERE match_id = :matchId
              AND sender_user_id <> :userId
              AND sequence_number > :afterSequence
              AND deleted_at IS NULL
              AND moderation_status = 'APPROVED'
            """;

    public Optional<MessageRow> findByIdempotencyKey(UUID senderUserId, UUID clientMessageId) {
        var params = new MapSqlParameterSource()
                .addValue("senderUserId", senderUserId)
                .addValue("clientMessageId", clientMessageId);
        return jdbc.query(FIND_BY_IDEMPOTENCY_SQL, params, this::mapRow).stream().findFirst();
    }

    public MessageRow insert(UUID matchId, UUID senderUserId, UUID clientMessageId,
                             String messageType, String body, long sequenceNumber) {
        var params = new MapSqlParameterSource()
                .addValue("matchId", matchId)
                .addValue("senderUserId", senderUserId)
                .addValue("clientMessageId", clientMessageId)
                .addValue("messageType", messageType)
                .addValue("body", body)
                .addValue("sequenceNumber", sequenceNumber);
        return jdbc.queryForObject(INSERT_MESSAGE_SQL, params, this::mapRow);
    }

    public List<MessageRow> getMessagesBefore(UUID matchId, long beforeSequence, int limit) {
        var params = new MapSqlParameterSource()
                .addValue("matchId", matchId)
                .addValue("beforeSequence", beforeSequence)
                .addValue("limit", limit + 1);
        return jdbc.query(GET_MESSAGES_BEFORE_SQL, params, this::mapRow);
    }

    public List<MessageRow> getMessagesAfter(UUID matchId, long afterSequence, int limit) {
        var params = new MapSqlParameterSource()
                .addValue("matchId", matchId)
                .addValue("afterSequence", afterSequence)
                .addValue("limit", limit + 1);
        return jdbc.query(GET_MESSAGES_AFTER_SQL, params, this::mapRow);
    }

    public List<MessageRow> getLatestMessages(UUID matchId, int limit) {
        var params = new MapSqlParameterSource()
                .addValue("matchId", matchId)
                .addValue("limit", limit + 1);
        List<MessageRow> rows = jdbc.query(GET_LATEST_MESSAGES_SQL, params, this::mapRow);
        rows = new java.util.ArrayList<>(rows);
        java.util.Collections.reverse(rows);
        return rows;
    }

    public Optional<MessageRow> getLastMessage(UUID matchId) {
        var params = new MapSqlParameterSource("matchId", matchId);
        return jdbc.query(GET_LAST_MESSAGE_SQL, params, this::mapRow).stream().findFirst();
    }

    public int countUnread(UUID matchId, UUID userId, long afterSequence) {
        var params = new MapSqlParameterSource()
                .addValue("matchId", matchId)
                .addValue("userId", userId)
                .addValue("afterSequence", afterSequence);
        Integer count = jdbc.queryForObject(COUNT_UNREAD_SQL, params, Integer.class);
        return count != null ? count : 0;
    }

    private MessageRow mapRow(ResultSet rs, int n) throws SQLException {
        return new MessageRow(
                rs.getObject("id", UUID.class),
                rs.getObject("match_id", UUID.class),
                rs.getLong("sequence_number"),
                rs.getObject("sender_user_id", UUID.class),
                rs.getString("message_type"),
                rs.getString("body"),
                rs.getString("moderation_status"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("deleted_at", OffsetDateTime.class)
        );
    }
}
