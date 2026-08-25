package com.qaliye.backend.actions;

import com.qaliye.backend.billing.repository.ActionLimitRepository;
import com.qaliye.backend.billing.service.ActionCostService;
import com.qaliye.backend.billing.service.CreditService;
import com.qaliye.backend.discovery.exception.ActionLimitExceededException;
import com.qaliye.backend.notifications.NotificationDispatcher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SwipeService {

    public record SwipeResult(boolean matched, UUID matchId, UUID rewindEligibleUntil) {
        public SwipeResult(boolean matched, UUID matchId) {
            this(matched, matchId, null);
        }
    }

    private record ActionRow(UUID id, UUID targetUserId, String actionType) {}
    private record MatchRow(UUID id, OffsetDateTime rewindEligibleUntil, OffsetDateTime firstMessageAt) {}

    private static final int REWIND_GRACE_SECONDS = 60;

    private static final String VERIFY_TARGET_SQL = """
            SELECT 1 FROM profiles p
            JOIN app_users au ON au.id = p.user_id
            WHERE p.user_id = :targetId
              AND p.is_onboarded = TRUE
              AND p.is_visible = TRUE
              AND au.status = 'ACTIVE'
            """;

    private static final String FIND_EXISTING_BY_CLIENT_ID_SQL = """
            SELECT id, target_user_id, action_type
            FROM user_discovery_actions
            WHERE actor_user_id = :callerId
              AND client_action_id = :clientActionId
            """;

    private static final String FIND_ACTIVE_ACTION_FOR_PAIR_SQL = """
            SELECT id FROM user_discovery_actions
            WHERE actor_user_id = :callerId
              AND target_user_id = :targetId
              AND status = 'ACTIVE'
            """;

    private static final String INSERT_ACTION_SQL = """
            INSERT INTO user_discovery_actions
                (actor_user_id, target_user_id, action_type, status, client_action_id)
            VALUES (:callerId, :targetId, :actionType, 'ACTIVE', :clientActionId)
            RETURNING id
            """;

    private static final String CHECK_RECIPROCAL_SQL = """
            SELECT id FROM user_discovery_actions
            WHERE actor_user_id = :targetId
              AND target_user_id = :callerId
              AND action_type IN ('LIKE', 'SUPERLIKE')
              AND status = 'ACTIVE'
            """;

    private static final String FIND_ACTIVE_MATCH_SQL = """
            SELECT id FROM matches
            WHERE user_one_id = :userOneId
              AND user_two_id = :userTwoId
              AND status = 'ACTIVE'
            """;

    private static final String INSERT_MATCH_SQL = """
            INSERT INTO matches
                (user_one_id, user_two_id,
                 user_one_like_action_id, user_two_like_action_id,
                 created_by_action_id, rewind_eligible_until)
            VALUES
                (:userOneId, :userTwoId,
                 :userOneLikeActionId, :userTwoLikeActionId,
                 :createdByActionId,
                 NOW() + INTERVAL '60 seconds')
            RETURNING id
            """;

    private static final String FETCH_LAST_ACTIVE_ACTION_SQL = """
            SELECT id, target_user_id, action_type
            FROM user_discovery_actions
            WHERE actor_user_id = :callerId
              AND status = 'ACTIVE'
            ORDER BY created_at DESC
            LIMIT 1
            FOR UPDATE
            """;

    private static final String FIND_ACTIVE_MATCH_BY_ACTION_SQL = """
            SELECT id, rewind_eligible_until, first_message_at
            FROM matches
            WHERE (created_by_action_id = :actionId
                   OR user_one_like_action_id = :actionId
                   OR user_two_like_action_id = :actionId)
              AND status = 'ACTIVE'
            FOR UPDATE
            """;

    private static final String END_MATCH_BY_REWIND_SQL = """
            UPDATE matches SET
                status = 'ENDED',
                end_reason = 'CANCELLED_BY_REWIND',
                ended_by_user_id = :callerId,
                ended_at = NOW(),
                updated_at = NOW()
            WHERE id = :matchId
            """;

    private static final String REVERSE_ACTION_SQL = """
            UPDATE user_discovery_actions SET
                status = 'REVERSED',
                reversed_at = NOW(),
                reversed_reason = 'USER_REWIND'
            WHERE id = :actionId
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ActionCostService actionCostService;
    private final ActionLimitRepository actionLimitRepo;
    private final CreditService creditService;
    private final NotificationDispatcher notificationDispatcher;

    public SwipeService(NamedParameterJdbcTemplate jdbc,
                        ActionCostService actionCostService,
                        ActionLimitRepository actionLimitRepo,
                        CreditService creditService,
                        NotificationDispatcher notificationDispatcher) {
        this.jdbc = jdbc;
        this.actionCostService = actionCostService;
        this.actionLimitRepo = actionLimitRepo;
        this.creditService = creditService;
        this.notificationDispatcher = notificationDispatcher;
    }

    @Transactional
    public SwipeResult swipe(UUID callerId, SwipeRequest request) {
        UUID targetId = request.getTargetUserId();
        String actionType = request.getActionType();
        UUID clientActionId = request.getClientActionId();

        if (callerId.equals(targetId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cannot_swipe_self");
        }

        // Idempotency: same clientActionId already processed
        List<ActionRow> existing = jdbc.query(FIND_EXISTING_BY_CLIENT_ID_SQL,
                Map.of("callerId", callerId, "clientActionId", clientActionId),
                (rs, rowNum) -> new ActionRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("target_user_id", UUID.class),
                        rs.getString("action_type")));
        if (!existing.isEmpty()) {
            UUID existingActionId = existing.get(0).id();
            return buildIdempotentResult(callerId, targetId, existingActionId);
        }

        // Check target is eligible
        List<Integer> targetExists = jdbc.query(VERIFY_TARGET_SQL, Map.of("targetId", targetId),
                (rs, rowNum) -> rs.getInt(1));
        if (targetExists.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "target_not_found");
        }

        // Check for active action already existing for this pair (different clientActionId)
        List<UUID> activePair = jdbc.query(FIND_ACTIVE_ACTION_FOR_PAIR_SQL,
                Map.of("callerId", callerId, "targetId", targetId),
                (rs, rowNum) -> rs.getObject("id", UUID.class));
        if (!activePair.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ACTIVE_ACTION_ALREADY_EXISTS");
        }

        // PASS does not consume quota and requires no further processing
        if ("PASS".equals(actionType)) {
            jdbc.update(INSERT_ACTION_SQL,
                    Map.of("callerId", callerId, "targetId", targetId,
                           "actionType", actionType, "clientActionId", clientActionId));
            return new SwipeResult(false, null);
        }

        // Enforce action cost/limit atomically to prevent TOCTOU races
        String featureCode = "SUPERLIKE".equals(actionType) ? "SUPER_LIKE" : "LIKE";
        ActionCostService.ActionCostResult cost = actionCostService.evaluate(callerId, featureCode);

        if (cost.ruleId() != null && cost.limitValue() != null) {
            // Ensure tracker row exists, then atomically increment iff still under limit
            actionLimitRepo.ensureExists(callerId, cost.ruleId(), cost.periodStart(), cost.periodEnd());
            boolean incremented = actionLimitRepo
                    .tryIncrementUnderLimit(callerId, cost.ruleId(), cost.periodStart(), cost.limitValue())
                    .isPresent();
            if (!incremented && !cost.requiresCredits()) {
                throw new ActionLimitExceededException("SUPERLIKE".equals(actionType) ? "SUPER_LIKE" : "LIKE", cost.periodType());
            }
        } else if (cost.isBlocked()) {
            throw new ActionLimitExceededException("SUPERLIKE".equals(actionType) ? "SUPER_LIKE" : "LIKE", cost.periodType());
        }

        if (cost.requiresCredits()) {
            String idemKey = "swipe-" + callerId + "-" + clientActionId;
            creditService.consumeCredits(callerId, cost.creditCost(), featureCode, idemKey);
        }

        // Insert the action
        List<UUID> insertedIds = jdbc.query(INSERT_ACTION_SQL,
                Map.of("callerId", callerId, "targetId", targetId,
                       "actionType", actionType, "clientActionId", clientActionId),
                (rs, rowNum) -> rs.getObject("id", UUID.class));
        UUID newActionId = insertedIds.get(0);

        // Acquire advisory lock on the canonical user pair to prevent a race
        // condition where both users like each other simultaneously and neither
        // transaction sees the other's uncommitted like, resulting in no match.
        UUID[] canonicalPair = canonical(callerId, targetId);
        jdbc.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtext(:pairKey))",
                Map.of("pairKey", canonicalPair[0].toString() + ":" + canonicalPair[1].toString()),
                Object.class);

        // Check for reciprocal like/superlike
        List<UUID> reciprocalIds = jdbc.query(CHECK_RECIPROCAL_SQL,
                Map.of("targetId", targetId, "callerId", callerId),
                (rs, rowNum) -> rs.getObject("id", UUID.class));

        if (reciprocalIds.isEmpty()) {
            return new SwipeResult(false, null);
        }

        UUID reciprocalActionId = reciprocalIds.get(0);
        UUID[] canonical = canonical(callerId, targetId);
        UUID userOneId = canonical[0];
        UUID userTwoId = canonical[1];

        // Determine which action belongs to user_one and user_two
        boolean callerIsUserOne = callerId.equals(userOneId);
        UUID userOneLikeActionId = callerIsUserOne ? newActionId : reciprocalActionId;
        UUID userTwoLikeActionId = callerIsUserOne ? reciprocalActionId : newActionId;

        UUID matchId = createMatch(userOneId, userTwoId,
                userOneLikeActionId, userTwoLikeActionId, newActionId);

        notificationDispatcher.dispatchMatchNotification(callerId, targetId, matchId);
        return new SwipeResult(true, matchId);
    }

    @Transactional
    public UUID rewind(UUID callerId) {
        ActionCostService.ActionCostResult cost = actionCostService.evaluate(callerId, "REWIND");

        if (cost.ruleId() != null && cost.limitValue() != null) {
            actionLimitRepo.ensureExists(callerId, cost.ruleId(), cost.periodStart(), cost.periodEnd());
            boolean incremented = actionLimitRepo
                    .tryIncrementUnderLimit(callerId, cost.ruleId(), cost.periodStart(), cost.limitValue())
                    .isPresent();
            if (!incremented && !cost.requiresCredits()) {
                throw new ActionLimitExceededException("REWIND", cost.periodType());
            }
        } else if (cost.isBlocked()) {
            throw new ActionLimitExceededException("REWIND", cost.periodType());
        }

        // Find the latest ACTIVE action for this user
        MapSqlParameterSource callerParams = new MapSqlParameterSource("callerId", callerId);
        List<ActionRow> lastActions = jdbc.query(FETCH_LAST_ACTIVE_ACTION_SQL, callerParams,
                (rs, rowNum) -> new ActionRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("target_user_id", UUID.class),
                        rs.getString("action_type")));

        if (lastActions.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no_action_to_rewind");
        }

        ActionRow lastAction = lastActions.get(0);
        UUID actionId = lastAction.id();
        UUID targetId = lastAction.targetUserId();

        // Check if this action created an active match
        List<MatchRow> matchRows = jdbc.query(FIND_ACTIVE_MATCH_BY_ACTION_SQL,
                Map.of("actionId", actionId),
                (rs, rowNum) -> new MatchRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("rewind_eligible_until", OffsetDateTime.class),
                        rs.getObject("first_message_at", OffsetDateTime.class)));

        if (!matchRows.isEmpty()) {
            MatchRow match = matchRows.get(0);
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

            // Match rewind only allowed within grace period and before first message
            if (match.rewindEligibleUntil() == null || now.isAfter(match.rewindEligibleUntil())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "rewind_grace_period_expired");
            }
            if (match.firstMessageAt() != null) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "rewind_not_allowed_after_first_message");
            }

            // End the match as cancelled by rewind
            jdbc.update(END_MATCH_BY_REWIND_SQL,
                    Map.of("callerId", callerId, "matchId", match.id()));
        }

        // Reverse the action (tracker already incremented above within the lock)
        jdbc.update(REVERSE_ACTION_SQL, Map.of("actionId", actionId));

        if (cost.requiresCredits()) {
            creditService.consumeCredits(callerId, cost.creditCost(), "REWIND", "rewind-" + actionId);
        }

        return targetId;
    }

    private SwipeResult buildIdempotentResult(UUID callerId, UUID targetId, UUID actionId) {
        UUID[] canonical = canonical(callerId, targetId);
        List<UUID> matchIds = jdbc.query(FIND_ACTIVE_MATCH_SQL,
                Map.of("userOneId", canonical[0], "userTwoId", canonical[1]),
                (rs, rowNum) -> rs.getObject("id", UUID.class));
        return matchIds.isEmpty()
                ? new SwipeResult(false, null)
                : new SwipeResult(true, matchIds.get(0));
    }

    private UUID createMatch(UUID userOneId, UUID userTwoId,
                             UUID userOneLikeActionId, UUID userTwoLikeActionId,
                             UUID createdByActionId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userOneId", userOneId)
                .addValue("userTwoId", userTwoId)
                .addValue("userOneLikeActionId", userOneLikeActionId)
                .addValue("userTwoLikeActionId", userTwoLikeActionId)
                .addValue("createdByActionId", createdByActionId);

        try {
            List<UUID> ids = jdbc.query(INSERT_MATCH_SQL, params,
                    (rs, rowNum) -> rs.getObject("id", UUID.class));
            return ids.get(0);
        } catch (DataIntegrityViolationException e) {
            // Concurrent insert or already matched — return existing active match
            List<UUID> existing = jdbc.query(FIND_ACTIVE_MATCH_SQL,
                    Map.of("userOneId", userOneId, "userTwoId", userTwoId),
                    (rs, rowNum) -> rs.getObject("id", UUID.class));
            if (!existing.isEmpty()) return existing.get(0);
            throw e;
        }
    }

    private UUID[] canonical(UUID a, UUID b) {
        return a.compareTo(b) < 0 ? new UUID[]{a, b} : new UUID[]{b, a};
    }
}
