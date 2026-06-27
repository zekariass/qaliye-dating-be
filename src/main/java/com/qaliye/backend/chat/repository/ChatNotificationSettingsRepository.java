package com.qaliye.backend.chat.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ChatNotificationSettingsRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ChatNotificationSettingsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record SettingsRow(UUID matchId, UUID userId, OffsetDateTime mutedUntil) {}

    private static final String FIND_SQL = """
            SELECT match_id, user_id, muted_until
            FROM match_notification_settings
            WHERE match_id = :matchId AND user_id = :userId
            """;

    private static final String UPSERT_SQL = """
            INSERT INTO match_notification_settings (match_id, user_id, muted_until)
            VALUES (:matchId, :userId, :mutedUntil)
            ON CONFLICT (match_id, user_id) DO UPDATE
            SET muted_until = EXCLUDED.muted_until,
                updated_at  = NOW()
            """;

    public Optional<SettingsRow> find(UUID matchId, UUID userId) {
        var params = new MapSqlParameterSource()
                .addValue("matchId", matchId)
                .addValue("userId", userId);
        List<SettingsRow> rows = jdbc.query(FIND_SQL, params,
                (rs, n) -> new SettingsRow(
                        rs.getObject("match_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("muted_until", OffsetDateTime.class)));
        return rows.stream().findFirst();
    }

    public void upsert(UUID matchId, UUID userId, OffsetDateTime mutedUntil) {
        var params = new MapSqlParameterSource()
                .addValue("matchId", matchId)
                .addValue("userId", userId)
                .addValue("mutedUntil", mutedUntil);
        jdbc.update(UPSERT_SQL, params);
    }
}
