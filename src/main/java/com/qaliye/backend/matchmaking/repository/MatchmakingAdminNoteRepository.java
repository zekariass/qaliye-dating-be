package com.qaliye.backend.matchmaking.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class MatchmakingAdminNoteRepository {

    public record NoteRow(
            UUID id,
            UUID requestId,
            UUID adminUserId,
            String note,
            OffsetDateTime createdAt) {}

    private static final String INSERT_SQL = """
            INSERT INTO matchmaking_admin_notes (request_id, admin_user_id, note)
            VALUES (:requestId, :adminUserId, :note)
            RETURNING id
            """;

    private static final String FIND_BY_REQUEST_SQL = """
            SELECT id, request_id, admin_user_id, note, created_at
            FROM matchmaking_admin_notes
            WHERE request_id = :requestId
            ORDER BY created_at ASC
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public MatchmakingAdminNoteRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(UUID requestId, UUID adminUserId, String note) {
        var params = new MapSqlParameterSource()
                .addValue("requestId", requestId)
                .addValue("adminUserId", adminUserId)
                .addValue("note", note);
        return jdbc.query(INSERT_SQL, params, (rs, i) -> rs.getObject("id", UUID.class))
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Failed to insert admin note"));
    }

    public List<NoteRow> findByRequestId(UUID requestId) {
        return jdbc.query(FIND_BY_REQUEST_SQL, new MapSqlParameterSource("requestId", requestId),
                this::mapRow);
    }

    // ── Mappers ────────────────────────────────────────────────────────────

    private NoteRow mapRow(ResultSet rs, int i) throws SQLException {
        return new NoteRow(
                rs.getObject("id", UUID.class),
                rs.getObject("request_id", UUID.class),
                rs.getObject("admin_user_id", UUID.class),
                rs.getString("note"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
