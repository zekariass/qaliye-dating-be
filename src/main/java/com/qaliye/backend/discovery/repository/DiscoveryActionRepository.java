package com.qaliye.backend.discovery.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DiscoveryActionRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public DiscoveryActionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ActionRow(UUID id, UUID actorUserId, UUID targetUserId,
                            String actionType, String status, UUID clientActionId,
                            OffsetDateTime createdAt) {}

    private static final String FIND_BY_CLIENT_ACTION_ID = """
            SELECT id, actor_user_id, target_user_id, action_type, status, client_action_id, created_at
            FROM user_discovery_actions
            WHERE actor_user_id = :actorId
              AND client_action_id = :clientActionId
            """;

    private static final String FIND_ACTIVE_BY_PAIR = """
            SELECT id, actor_user_id, target_user_id, action_type, status, client_action_id, created_at
            FROM user_discovery_actions
            WHERE actor_user_id = :actorId
              AND target_user_id = :targetId
              AND status = 'ACTIVE'
            """;

    private static final String FIND_LAST_REWINDABLE = """
            SELECT id, actor_user_id, target_user_id, action_type, status, client_action_id, created_at
            FROM user_discovery_actions
            WHERE actor_user_id = :actorId
              AND status = 'ACTIVE'
            ORDER BY created_at DESC
            LIMIT 1
            FOR UPDATE
            """;

    private static final String INSERT_ACTION = """
            INSERT INTO user_discovery_actions
                (actor_user_id, target_user_id, action_type, status, client_action_id)
            VALUES
                (:actorId, :targetId, :actionType, 'ACTIVE', :clientActionId)
            RETURNING id, actor_user_id, target_user_id, action_type, status, client_action_id, created_at
            """;

    private static final String REVERSE_ACTION = """
            UPDATE user_discovery_actions
            SET status = 'REVERSED',
                reversed_at = NOW(),
                reversed_reason = 'USER_REWIND'
            WHERE id = :actionId
              AND status = 'ACTIVE'
            """;

    private static final String REVERSE_PASS_FOR_REVISIT = """
            UPDATE user_discovery_actions
            SET status = 'REVERSED',
                reversed_at = NOW(),
                reversed_reason = 'REVISIT_PASSES'
            WHERE id = :actionId
              AND status = 'ACTIVE'
              AND action_type = 'PASS'
            """;

    private static final String FIND_MUTUAL_ACTIVE_LIKE = """
            SELECT id, actor_user_id, target_user_id, action_type, status, client_action_id, created_at
            FROM user_discovery_actions
            WHERE actor_user_id = :targetId
              AND target_user_id = :actorId
              AND action_type IN ('LIKE', 'SUPERLIKE')
              AND status = 'ACTIVE'
            """;

    public Optional<ActionRow> findByClientActionId(UUID actorId, UUID clientActionId) {
        var params = new MapSqlParameterSource()
                .addValue("actorId", actorId)
                .addValue("clientActionId", clientActionId);
        return jdbc.query(FIND_BY_CLIENT_ACTION_ID, params, this::mapRow).stream().findFirst();
    }

    public Optional<ActionRow> findActiveByPair(UUID actorId, UUID targetId) {
        var params = new MapSqlParameterSource()
                .addValue("actorId", actorId)
                .addValue("targetId", targetId);
        return jdbc.query(FIND_ACTIVE_BY_PAIR, params, this::mapRow).stream().findFirst();
    }

    public Optional<ActionRow> findLastRewindable(UUID actorId) {
        var params = new MapSqlParameterSource().addValue("actorId", actorId);
        return jdbc.query(FIND_LAST_REWINDABLE, params, this::mapRow).stream().findFirst();
    }

    public Optional<ActionRow> findMutualActiveLike(UUID actorId, UUID targetId) {
        var params = new MapSqlParameterSource()
                .addValue("actorId", actorId)
                .addValue("targetId", targetId);
        return jdbc.query(FIND_MUTUAL_ACTIVE_LIKE, params, this::mapRow).stream().findFirst();
    }

    public ActionRow insertAction(UUID actorId, UUID targetId, String actionType, UUID clientActionId) {
        var params = new MapSqlParameterSource()
                .addValue("actorId", actorId)
                .addValue("targetId", targetId)
                .addValue("actionType", actionType)
                .addValue("clientActionId", clientActionId);
        ActionRow row = jdbc.query(INSERT_ACTION, params, rs -> {
            if (!rs.next()) return null;
            return mapRow(rs, 0);
        });
        if (row == null) {
            return findByClientActionId(actorId, clientActionId)
                    .orElseThrow(() -> new IllegalStateException("Insert action returned no row"));
        }
        return row;
    }

    public int reverseAction(UUID actionId) {
        return jdbc.update(REVERSE_ACTION, new MapSqlParameterSource("actionId", actionId));
    }

    public int reversePassForRevisit(UUID actionId) {
        return jdbc.update(REVERSE_PASS_FOR_REVISIT, new MapSqlParameterSource("actionId", actionId));
    }

    private ActionRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Object createdAtObj = rs.getObject("created_at");
        OffsetDateTime createdAt = createdAtObj instanceof OffsetDateTime odt ? odt : null;
        return new ActionRow(
                rs.getObject("id", UUID.class),
                rs.getObject("actor_user_id", UUID.class),
                rs.getObject("target_user_id", UUID.class),
                rs.getString("action_type"),
                rs.getString("status"),
                rs.getObject("client_action_id", UUID.class),
                createdAt
        );
    }
}
