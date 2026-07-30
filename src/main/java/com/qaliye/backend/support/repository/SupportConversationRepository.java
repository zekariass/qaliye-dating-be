package com.qaliye.backend.support.repository;

import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SupportConversationRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public SupportConversationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ConversationRow(
            UUID id,
            UUID userId,
            String userDisplayName,
            String status,
            int priority,
            UUID assignedStaffUserId,
            long nextPublicSequence,
            long userLastReadSequence,
            long staffLastReadSequence,
            OffsetDateTime lastPublicMessageAt,
            String lastPublicMessageSenderType,
            OffsetDateTime waitingSince,
            OffsetDateTime firstStaffResponseAt,
            OffsetDateTime lastActivityAt,
            OffsetDateTime closedAt,
            UUID closedByAppUserId,
            String closedByType,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {}

    private static final String SELECT_CONV_COLUMNS = """
            SELECT c.id, c.user_id, p.display_name AS user_display_name,
                   c.status, c.priority, c.assigned_staff_user_id,
                   c.next_public_sequence, c.user_last_read_sequence, c.staff_last_read_sequence,
                   c.last_public_message_at, c.last_public_message_sender_type,
                   c.waiting_since, c.first_staff_response_at, c.last_activity_at,
                   c.closed_at, c.closed_by_app_user_id, c.closed_by_type,
                   c.created_at, c.updated_at
            FROM public.support_conversations c
            LEFT JOIN profiles p ON p.user_id = c.user_id
            LEFT JOIN app_users au ON au.id = c.user_id
            """;

    private ConversationRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ConversationRow(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("user_display_name"),
                rs.getString("status"),
                rs.getInt("priority"),
                rs.getObject("assigned_staff_user_id", UUID.class),
                rs.getLong("next_public_sequence"),
                rs.getLong("user_last_read_sequence"),
                rs.getLong("staff_last_read_sequence"),
                rs.getObject("last_public_message_at", OffsetDateTime.class),
                rs.getString("last_public_message_sender_type"),
                rs.getObject("waiting_since", OffsetDateTime.class),
                rs.getObject("first_staff_response_at", OffsetDateTime.class),
                rs.getObject("last_activity_at", OffsetDateTime.class),
                rs.getObject("closed_at", OffsetDateTime.class),
                rs.getObject("closed_by_app_user_id", UUID.class),
                rs.getString("closed_by_type"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    public Optional<ConversationRow> findByUserId(UUID userId) {
        List<ConversationRow> rows = jdbc.query(
                SELECT_CONV_COLUMNS + "WHERE c.user_id = :userId",
                Map.of("userId", userId),
                this::mapRow
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<ConversationRow> findById(UUID conversationId) {
        List<ConversationRow> rows = jdbc.query(
                SELECT_CONV_COLUMNS + "WHERE c.id = :id",
                Map.of("id", conversationId),
                this::mapRow
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public long findMyReadSequence(UUID conversationId, UUID staffUserId) {
        List<Long> rows = jdbc.query(
                """
                SELECT last_read_sequence
                FROM public.support_conversation_staff_reads
                WHERE conversation_id = :convId AND staff_user_id = :staffId
                """,
                Map.of("convId", conversationId, "staffId", staffUserId),
                (rs, rowNum) -> rs.getLong("last_read_sequence")
        );
        return rows.isEmpty() ? 0L : rows.get(0);
    }

    public List<ConversationRow> listForQueue(
            String status,
            UUID assignedToStaffId,
            Boolean unassignedOnly,
            Integer priority,
            int limit,
            int offset) {

        StringBuilder sql = new StringBuilder(SELECT_CONV_COLUMNS + " WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();

        sql.append(" AND (au.status IS NULL OR au.status != 'DELETED')");

        if (status != null && !status.isBlank()) {
            sql.append(" AND c.status = :status");
            params.addValue("status", status);
        } else {
            sql.append(" AND c.status != 'IDLE'");
        }

        if (assignedToStaffId != null) {
            sql.append(" AND c.assigned_staff_user_id = :assignedTo");
            params.addValue("assignedTo", assignedToStaffId);
        }

        if (Boolean.TRUE.equals(unassignedOnly)) {
            sql.append(" AND c.assigned_staff_user_id IS NULL");
        }

        if (priority != null) {
            sql.append(" AND c.priority = :priority");
            params.addValue("priority", priority);
        }

        sql.append(" ORDER BY c.priority ASC, c.waiting_since ASC NULLS LAST, c.id ASC");
        sql.append(" LIMIT :limit OFFSET :offset");
        params.addValue("limit", limit);
        params.addValue("offset", offset);

        return jdbc.query(sql.toString(), params, this::mapRow);
    }

    public void callMarkReadByUser(UUID conversationId, UUID userId, long lastReadSequence) {
        jdbc.query(
                "SELECT public.mark_support_conversation_read_by_user(:convId, :userId, :seq)",
                Map.of("convId", conversationId, "userId", userId, "seq", lastReadSequence),
                rs -> {}
        );
    }

    public void callMarkReadByStaff(UUID conversationId, UUID staffUserId, long lastReadSequence) {
        jdbc.query(
                "SELECT public.mark_support_conversation_read_by_staff(:convId, :staffId, :seq)",
                Map.of("convId", conversationId, "staffId", staffUserId, "seq", lastReadSequence),
                rs -> {}
        );
    }

    public void callCloseByUser(UUID conversationId, UUID userId) {
        jdbc.query(
                "SELECT public.close_support_conversation_by_user(:convId, :userId)",
                Map.of("convId", conversationId, "userId", userId),
                rs -> {}
        );
    }

    public void callCloseByStaff(UUID conversationId, UUID staffUserId) {
        jdbc.query(
                "SELECT public.close_support_conversation_by_staff(:convId, :staffId)",
                Map.of("convId", conversationId, "staffId", staffUserId),
                rs -> {}
        );
    }

    public void callReopen(UUID conversationId, UUID staffUserId) {
        jdbc.query(
                "SELECT public.reopen_support_conversation_by_staff(:convId, :staffId)",
                Map.of("convId", conversationId, "staffId", staffUserId),
                rs -> {}
        );
    }

    public void callAssign(UUID conversationId, UUID actorStaffUserId, UUID assignedStaffUserId) {
        jdbc.query(
                "SELECT public.assign_support_conversation(:convId, :actor, :assigned)",
                Map.of("convId", conversationId,
                       "actor", actorStaffUserId,
                       "assigned", assignedStaffUserId),
                rs -> {}
        );
    }

    public void callSetPriority(UUID conversationId, UUID actorStaffUserId, int priority) {
        jdbc.query(
                "SELECT public.set_support_conversation_priority(:convId, :actor, :priority::smallint)",
                Map.of("convId", conversationId,
                       "actor", actorStaffUserId,
                       "priority", priority),
                rs -> {}
        );
    }

    static PGobject toJsonb(String json) {
        try {
            PGobject obj = new PGobject();
            obj.setType("jsonb");
            obj.setValue(json);
            return obj;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create PGobject for JSONB", e);
        }
    }
}
