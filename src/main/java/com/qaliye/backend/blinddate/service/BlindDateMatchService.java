package com.qaliye.backend.blinddate.service;

import com.qaliye.backend.notifications.NotificationDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * Creates the system-generated mutual LIKE pair and the resulting match when a
 * Blind Date reveal ends in mutual interest.
 *
 * <p>Both actions are written with {@code action_variant_code = 'BLIND_DATE'}
 * and {@code action_source = 'BLIND_DATE'} so they are excluded from rewind
 * (see {@code SwipeService.FETCH_LAST_ACTIVE_ACTION_SQL}) and remain
 * distinguishable from organic discovery likes. No credits are charged and no
 * LIKE usage limits are consumed — the paid actions were already charged at
 * session create / participate time.
 */
@Service
public class BlindDateMatchService {

    private static final Logger log = LoggerFactory.getLogger(BlindDateMatchService.class);

    public record MatchResult(boolean matched, UUID matchId) {}

    private static final String BLOCK_EXISTS_SQL = """
            SELECT COUNT(1) FROM user_blocks
            WHERE status = 'ACTIVE'
              AND ((blocker_user_id = :userA AND blocked_user_id = :userB)
                OR (blocker_user_id = :userB AND blocked_user_id = :userA))
            """;

    private static final String FIND_ACTIVE_MATCH_SQL = """
            SELECT id FROM matches
            WHERE user_one_id = :userOneId
              AND user_two_id = :userTwoId
              AND status = 'ACTIVE'
            """;

    private static final String FIND_ACTIVE_LIKE_FOR_PAIR_SQL = """
            SELECT id FROM user_discovery_actions
            WHERE actor_user_id = :actorId
              AND target_user_id = :targetId
              AND action_type IN ('LIKE', 'SUPERLIKE')
              AND status = 'ACTIVE'
            ORDER BY created_at DESC
            LIMIT 1
            """;

    private static final String INSERT_BLIND_DATE_LIKE_SQL = """
            INSERT INTO user_discovery_actions
                (actor_user_id, target_user_id, action_type, status, client_action_id,
                 action_variant_code, action_source, metadata)
            VALUES (:actorId, :targetId, 'LIKE', 'ACTIVE', :clientActionId,
                    'BLIND_DATE', 'BLIND_DATE', :metadata::jsonb)
            RETURNING id
            """;

    private static final String INSERT_MATCH_SQL = """
            INSERT INTO matches
                (user_one_id, user_two_id,
                 user_one_like_action_id, user_two_like_action_id,
                 created_by_action_id, match_source, rewind_eligible_until)
            VALUES
                (:userOneId, :userTwoId,
                 :userOneLikeActionId, :userTwoLikeActionId,
                 :createdByActionId, 'BLIND_DATE',
                 NOW() + INTERVAL '60 seconds')
            RETURNING id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final NotificationDispatcher notificationDispatcher;

    public BlindDateMatchService(NamedParameterJdbcTemplate jdbc,
                                 NotificationDispatcher notificationDispatcher) {
        this.jdbc = jdbc;
        this.notificationDispatcher = notificationDispatcher;
    }

    /**
     * Creates the blind-date match between the two users if possible.
     *
     * @return matched=true with the match id, or matched=false when a block
     *         exists between the pair or an active match already exists.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public MatchResult createBlindDateMatch(UUID userA, UUID userB, UUID sessionId) {
        if (isBlocked(userA, userB)) {
            log.info("Blind Date match skipped for session {}: block exists between {} and {}",
                    sessionId, userA, userB);
            return new MatchResult(false, null);
        }

        // PostgreSQL orders uuid values by unsigned bytes (memcmp); UUID.compareTo
        // uses signed long comparison which disagrees when first bytes straddle 0x80.
        // toString() comparison matches the DB ordering enforced by check_match_user_order.
        boolean aIsLower = userA.toString().compareTo(userB.toString()) < 0;
        UUID userOne = aIsLower ? userA : userB;
        UUID userTwo = aIsLower ? userB : userA;

        Optional<UUID> existingMatch = findActiveMatch(userOne, userTwo);
        if (existingMatch.isPresent()) {
            return new MatchResult(false, existingMatch.get());
        }

        UUID actionA = ensureBlindDateLike(userA, userB, sessionId);
        UUID actionB = ensureBlindDateLike(userB, userA, sessionId);

        UUID userOneAction = userOne.equals(userA) ? actionA : actionB;
        UUID userTwoAction = userOne.equals(userA) ? actionB : actionA;

        try {
            UUID matchId = insertMatch(userOne, userTwo, userOneAction, userTwoAction, actionA);
            notificationDispatcher.dispatchMatchNotification(userOne, userTwo, matchId);
            return new MatchResult(true, matchId);
        } catch (DataIntegrityViolationException e) {
            // Lost a concurrent insert race: the transaction is now aborted, so
            // a recovery query here would fail with 25P02 anyway (JPA tx
            // manager cannot do savepoints). Surface a retryable 409 — the
            // decide() retry re-reads state and resolves ALREADY_MATCHED.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "match_conflict", e);
        }
    }

    private boolean isBlocked(UUID userA, UUID userB) {
        var params = new MapSqlParameterSource().addValue("userA", userA).addValue("userB", userB);
        Integer count = jdbc.queryForObject(BLOCK_EXISTS_SQL, params, Integer.class);
        return count != null && count > 0;
    }

    private Optional<UUID> findActiveMatch(UUID userOne, UUID userTwo) {
        var params = new MapSqlParameterSource().addValue("userOneId", userOne).addValue("userTwoId", userTwo);
        return jdbc.query(FIND_ACTIVE_MATCH_SQL, params, (rs, i) -> rs.getObject("id", UUID.class))
                .stream().findFirst();
    }

    /**
     * Returns the id of an ACTIVE like for the pair, creating a BLIND_DATE one
     * when none exists. An existing ACTIVE like/superlike (any variant) is
     * reused — the unique_active_discovery_action_per_pair index forbids a
     * second active action for the pair. A previously REVERSED action is left
     * alone: a fresh BLIND_DATE row is inserted rather than resurrecting a like
     * the user deliberately undid.
     */
    private UUID ensureBlindDateLike(UUID actorId, UUID targetId, UUID sessionId) {
        var pairParams = new MapSqlParameterSource().addValue("actorId", actorId).addValue("targetId", targetId);
        Optional<UUID> existing = jdbc.query(FIND_ACTIVE_LIKE_FOR_PAIR_SQL, pairParams,
                        (rs, i) -> rs.getObject("id", UUID.class))
                .stream().findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }

        UUID clientActionId = deterministicClientActionId(actorId, targetId, sessionId);
        var params = new MapSqlParameterSource()
                .addValue("actorId", actorId)
                .addValue("targetId", targetId)
                .addValue("clientActionId", clientActionId)
                .addValue("metadata", "{\"blind_date_session_id\": \"" + sessionId + "\"}");
        try {
            return jdbc.query(INSERT_BLIND_DATE_LIKE_SQL, params, (rs, i) -> rs.getObject("id", UUID.class))
                    .stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException("Failed to insert blind date like"));
        } catch (DataIntegrityViolationException e) {
            // Concurrent insert for the pair: transaction is aborted, so the
            // recovery SELECT cannot run (no savepoints under JpaTransactionManager).
            throw new ResponseStatusException(HttpStatus.CONFLICT, "like_conflict", e);
        }
    }

    private UUID insertMatch(UUID userOne, UUID userTwo, UUID userOneAction,
                             UUID userTwoAction, UUID createdByActionId) {
        var params = new MapSqlParameterSource()
                .addValue("userOneId", userOne)
                .addValue("userTwoId", userTwo)
                .addValue("userOneLikeActionId", userOneAction)
                .addValue("userTwoLikeActionId", userTwoAction)
                .addValue("createdByActionId", createdByActionId);
        return jdbc.query(INSERT_MATCH_SQL, params, (rs, i) -> rs.getObject("id", UUID.class))
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Failed to insert blind date match"));
    }

    /**
     * Deterministic client_action_id so a retried reveal resolution cannot
     * insert a duplicate action for the same (actor, target, session) triple.
     */
    private UUID deterministicClientActionId(UUID actorId, UUID targetId, UUID sessionId) {
        String seed = "blind-date:" + sessionId + ":" + actorId + ":" + targetId;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}
