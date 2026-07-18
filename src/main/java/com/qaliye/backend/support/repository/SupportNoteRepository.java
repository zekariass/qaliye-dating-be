package com.qaliye.backend.support.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class SupportNoteRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public SupportNoteRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record NoteRow(
            UUID id,
            UUID conversationId,
            UUID staffUserId,
            String staffDisplayName,
            String body,
            OffsetDateTime createdAt
    ) {}

    private NoteRow mapNote(ResultSet rs, int rowNum) throws SQLException {
        return new NoteRow(
                rs.getObject("id", UUID.class),
                rs.getObject("conversation_id", UUID.class),
                rs.getObject("staff_user_id", UUID.class),
                rs.getString("staff_display_name"),
                rs.getString("body"),
                rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    public List<NoteRow> findNotes(UUID conversationId, int limit, int offset) {
        int capped = Math.min(limit, 100);
        return jdbc.query(
                """
                SELECT n.id, n.conversation_id, n.staff_user_id,
                       p.display_name AS staff_display_name,
                       n.body, n.created_at
                FROM public.support_internal_notes n
                LEFT JOIN public.profiles p ON p.user_id = n.staff_user_id
                WHERE n.conversation_id = :convId
                ORDER BY n.created_at DESC, n.id DESC
                LIMIT :limit OFFSET :offset
                """,
                Map.of("convId", conversationId, "limit", capped, "offset", offset),
                this::mapNote
        );
    }

    public NoteRow callAppendNote(
            UUID conversationId,
            UUID staffUserId,
            UUID clientNoteId,
            String body,
            String metadataJson) {

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("convId", conversationId)
                .addValue("staffId", staffUserId)
                .addValue("clientNoteId", clientNoteId)
                .addValue("body", body)
                .addValue("metadata", SupportConversationRepository.toJsonb(metadataJson));

        return jdbc.queryForObject(
                """
                SELECT n.id, n.conversation_id, n.staff_user_id,
                       p.display_name AS staff_display_name,
                       n.body, n.created_at
                FROM public.append_support_internal_note(
                    :convId, :staffId, :clientNoteId, :body, :metadata
                ) AS n
                LEFT JOIN public.profiles p ON p.user_id = n.staff_user_id
                """,
                params,
                this::mapNote
        );
    }
}
