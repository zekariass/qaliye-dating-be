package com.qaliye.backend.safety;

import com.qaliye.backend.chat.service.MatchLifecycleService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BlockService {

    private static final String VERIFY_USER_SQL =
            "SELECT 1 FROM app_users WHERE id = :blockedId";

    /**
     * Insert a new block, or reactivate a previously revoked block.
     * The partial unique index unique_active_block_per_direction prevents duplicate ACTIVE blocks.
     * The database trigger end_active_matches_when_blocked ends any active match automatically.
     */
    private static final String UPSERT_BLOCK_SQL = """
            INSERT INTO user_blocks (blocker_user_id, blocked_user_id, status, revoked_at)
            VALUES (:callerId, :blockedId, 'ACTIVE', NULL)
            ON CONFLICT (blocker_user_id, blocked_user_id)
            DO UPDATE SET
                status     = 'ACTIVE',
                revoked_at = NULL,
                updated_at = NOW()
            WHERE user_blocks.status = 'REVOKED'
            RETURNING id
            """;

    private static final String AUDIT_LOG_SQL = """
            INSERT INTO audit_log (actor_user_id, action, target_table, target_id, details)
            VALUES (:callerId, 'USER_BLOCK', 'app_users', :blockedId, :details::jsonb)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final MatchLifecycleService matchLifecycleService;

    public BlockService(NamedParameterJdbcTemplate jdbc,
                        MatchLifecycleService matchLifecycleService) {
        this.jdbc = jdbc;
        this.matchLifecycleService = matchLifecycleService;
    }

    @Transactional
    public void block(UUID callerId, UUID blockedId) {
        if (callerId.equals(blockedId)) {
            throw new SafetyException(400, "self_block");
        }

        List<Integer> userExists = jdbc.query(VERIFY_USER_SQL, Map.of("blockedId", blockedId),
                (rs, rowNum) -> rs.getInt(1));
        if (userExists.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user_not_found");
        }

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("callerId", callerId)
                .addValue("blockedId", blockedId);

        // End any active match before creating the block so outbox events are emitted.
        // The DB trigger end_active_matches_when_blocked remains defense-in-depth.
        matchLifecycleService.endMatchByPair(callerId, blockedId, "BLOCKED", callerId);

        jdbc.update(UPSERT_BLOCK_SQL, params);

        String details = "{\"blocked_user_id\": \"" + blockedId + "\"}";
        jdbc.update(AUDIT_LOG_SQL, params.addValue("details", details));
    }
}
