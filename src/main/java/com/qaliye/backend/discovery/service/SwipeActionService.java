package com.qaliye.backend.discovery.service;

import com.qaliye.backend.billing.repository.ActionLimitRepository;
import com.qaliye.backend.billing.service.ActionCostService;
import com.qaliye.backend.billing.service.CreditService;
import com.qaliye.backend.discovery.dto.MatchSummaryDto;
import com.qaliye.backend.discovery.dto.SwipeActionResponse;
import com.qaliye.backend.discovery.exception.ActionLimitExceededException;
import com.qaliye.backend.discovery.exception.TargetIneligibleException;
import com.qaliye.backend.discovery.repository.DiscoveryActionRepository;
import com.qaliye.backend.chat.service.MatchLifecycleService;
import com.qaliye.backend.notifications.NotificationDispatcher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class SwipeActionService {

    private final DiscoveryActionRepository actionRepo;
    private final ActionCostService actionCostService;
    private final ActionLimitRepository actionLimitRepo;
    private final CreditService creditService;
    private final MatchService matchService;
    private final NotificationDispatcher notificationDispatcher;
    private final MatchLifecycleService matchLifecycleService;
    private final NamedParameterJdbcTemplate jdbc;

    public SwipeActionService(DiscoveryActionRepository actionRepo,
                               ActionCostService actionCostService,
                               ActionLimitRepository actionLimitRepo,
                               CreditService creditService,
                               MatchService matchService,
                               NotificationDispatcher notificationDispatcher,
                               MatchLifecycleService matchLifecycleService,
                               NamedParameterJdbcTemplate jdbc) {
        this.actionRepo = actionRepo;
        this.actionCostService = actionCostService;
        this.actionLimitRepo = actionLimitRepo;
        this.creditService = creditService;
        this.matchService = matchService;
        this.notificationDispatcher = notificationDispatcher;
        this.matchLifecycleService = matchLifecycleService;
        this.jdbc = jdbc;
    }

    private static final String TARGET_ELIGIBILITY_SQL = """
            SELECT au.status, au.deleted_at, au.role, p.is_visible, p.is_onboarded
            FROM app_users au
            JOIN profiles p ON p.user_id = au.id
            WHERE au.id = :targetId
            """;

    private static final String ACTOR_ROLE_SQL = """
            SELECT role FROM app_users WHERE id = :actorId
            """;

    private static final String BLOCK_CHECK_SQL = """
            SELECT 1 FROM user_blocks
            WHERE status = 'ACTIVE'
              AND (
                  (blocker_user_id = :actorId AND blocked_user_id = :targetId)
                  OR
                  (blocker_user_id = :targetId AND blocked_user_id = :actorId)
              )
            LIMIT 1
            """;

    private static final String PRIMARY_PHOTO_CHECK_SQL = """
            SELECT 1 FROM profile_photos
            WHERE user_id = :targetId
              AND is_primary = TRUE
              AND moderation_status = 'APPROVED'
              AND deleted_at IS NULL
            LIMIT 1
            """;

    @Transactional
    public SwipeActionResponse recordLike(UUID actorId, UUID targetId, UUID clientActionId) {
        Optional<DiscoveryActionRepository.ActionRow> idempotent =
                actionRepo.findByClientActionId(actorId, clientActionId);
        if (idempotent.isPresent()) {
            return buildIdempotentResponse(idempotent.get(), actorId, "LIKE");
        }

        checkTargetEligibility(actorId, targetId);

        Optional<DiscoveryActionRepository.ActionRow> existingAction =
                actionRepo.findActiveByPair(actorId, targetId);
        if (existingAction.isPresent()) {
            if ("LIKE".equals(existingAction.get().actionType())) {
                return buildIdempotentResponse(existingAction.get(), actorId, "LIKE");
            }
            reverseExistingAction(existingAction.get(), actorId);
        }

        ActionCostService.ActionCostResult cost = actionCostService.evaluate(actorId, "LIKE");
        if (cost.isBlocked()) {
            throw new ActionLimitExceededException("LIKES", cost.periodType());
        }

        if (cost.requiresCredits()) {
            String idemKey = "like-" + clientActionId;
            creditService.consumeCredits(actorId, cost.creditCost(), "LIKE", idemKey);
        }

        DiscoveryActionRepository.ActionRow action =
                actionRepo.insertAction(actorId, targetId, "LIKE", clientActionId);

        if (cost.ruleId() != null && cost.limitValue() != null) {
            actionLimitRepo.ensureExists(actorId, cost.ruleId(), cost.periodStart(), cost.periodEnd());
            actionLimitRepo.findForUpdate(actorId, cost.ruleId(), cost.periodStart())
                    .ifPresent(t -> actionLimitRepo.increment(t.id()));
        }

        notificationDispatcher.dispatchLikeNotification(actorId, targetId, action.id());

        acquirePairLock(actorId, targetId);

        Optional<DiscoveryActionRepository.ActionRow> mutualAction =
                actionRepo.findMutualActiveLike(actorId, targetId);
        Optional<MatchSummaryDto> match = Optional.empty();
        if (mutualAction.isPresent()) {
            match = matchService.tryCreateMatch(actorId, targetId, action.id(), mutualAction.get().id());
            match.ifPresent(m ->
                    notificationDispatcher.dispatchMatchNotification(actorId, targetId, m.matchId()));
        }

        int likesRemaining = cost.limitValue() == null
                ? Integer.MAX_VALUE
                : Math.max(0, cost.limitValue() - cost.currentUsedCount() - 1);

        Instant createdAt = action.createdAt() != null ? action.createdAt().toInstant() : Instant.now();
        return new SwipeActionResponse(
                action.id(), "LIKE", "ACTIVE",
                match.isPresent(), match.orElse(null),
                likesRemaining, null, null,
                createdAt, false
        );
    }

    @Transactional
    public SwipeActionResponse recordPass(UUID actorId, UUID targetId, UUID clientActionId) {
        Optional<DiscoveryActionRepository.ActionRow> idempotent =
                actionRepo.findByClientActionId(actorId, clientActionId);
        if (idempotent.isPresent()) {
            return buildIdempotentResponse(idempotent.get(), actorId, "PASS");
        }

        checkBasicTargetEligibility(actorId, targetId);

        Optional<DiscoveryActionRepository.ActionRow> existingAction =
                actionRepo.findActiveByPair(actorId, targetId);
        if (existingAction.isPresent()) {
            if ("PASS".equals(existingAction.get().actionType())) {
                return buildIdempotentResponse(existingAction.get(), actorId, "PASS");
            }
            reverseExistingAction(existingAction.get(), actorId);
        }

        DiscoveryActionRepository.ActionRow action =
                actionRepo.insertAction(actorId, targetId, "PASS", clientActionId);

        Instant createdAt = action.createdAt() != null ? action.createdAt().toInstant() : Instant.now();
        return new SwipeActionResponse(
                action.id(), "PASS", "ACTIVE",
                false, null, null, null, null,
                createdAt, false
        );
    }

    @Transactional
    public SwipeActionResponse recordSuperLike(UUID actorId, UUID targetId, UUID clientActionId) {
        Optional<DiscoveryActionRepository.ActionRow> idempotent =
                actionRepo.findByClientActionId(actorId, clientActionId);
        if (idempotent.isPresent()) {
            return buildIdempotentResponse(idempotent.get(), actorId, "SUPERLIKE");
        }

        checkTargetEligibility(actorId, targetId);

        Optional<DiscoveryActionRepository.ActionRow> existingAction =
                actionRepo.findActiveByPair(actorId, targetId);
        if (existingAction.isPresent()) {
            if ("SUPERLIKE".equals(existingAction.get().actionType())) {
                return buildIdempotentResponse(existingAction.get(), actorId, "SUPERLIKE");
            }
            reverseExistingAction(existingAction.get(), actorId);
        }

        ActionCostService.ActionCostResult cost = actionCostService.evaluate(actorId, "SUPER_LIKE");
        if (cost.isBlocked()) {
            throw new ActionLimitExceededException("SUPER_LIKE", cost.periodType());
        }

        boolean usedCredit = cost.requiresCredits();
        if (usedCredit) {
            String idemKey = "superlike-" + clientActionId;
            creditService.consumeCredits(actorId, cost.creditCost(), "SUPER_LIKE", idemKey);
        }

        DiscoveryActionRepository.ActionRow action =
                actionRepo.insertAction(actorId, targetId, "SUPERLIKE", clientActionId);

        notificationDispatcher.dispatchSuperLikeNotification(actorId, targetId, action.id());

        acquirePairLock(actorId, targetId);

        if (cost.ruleId() != null && cost.limitValue() != null) {
            actionLimitRepo.ensureExists(actorId, cost.ruleId(), cost.periodStart(), cost.periodEnd());
            actionLimitRepo.findForUpdate(actorId, cost.ruleId(), cost.periodStart())
                    .ifPresent(t -> actionLimitRepo.increment(t.id()));
        }

        Optional<DiscoveryActionRepository.ActionRow> mutualAction =
                actionRepo.findMutualActiveLike(actorId, targetId);
        Optional<MatchSummaryDto> match = Optional.empty();
        if (mutualAction.isPresent()) {
            match = matchService.tryCreateMatch(actorId, targetId, action.id(), mutualAction.get().id());
            match.ifPresent(m ->
                    notificationDispatcher.dispatchMatchNotification(actorId, targetId, m.matchId()));
        }

        int superLikesRemaining = cost.limitValue() == null
                ? Integer.MAX_VALUE
                : Math.max(0, cost.limitValue() - cost.currentUsedCount() - (usedCredit ? 0 : 1));
        long creditBalance = usedCredit ? creditService.getBalance(actorId) : 0L;

        Instant createdAt = action.createdAt() != null ? action.createdAt().toInstant() : Instant.now();
        return new SwipeActionResponse(
                action.id(), "SUPERLIKE", "ACTIVE",
                match.isPresent(), match.orElse(null),
                null, superLikesRemaining, (int) creditBalance,
                createdAt, false
        );
    }

    private void checkTargetEligibility(UUID actorId, UUID targetId) {
        var params = new MapSqlParameterSource("targetId", targetId);
        String[] targetRole = new String[1];
        boolean eligible = Boolean.TRUE.equals(jdbc.query(TARGET_ELIGIBILITY_SQL, params, rs -> {
            if (!rs.next()) return false;
            targetRole[0] = rs.getString("role");
            return "ACTIVE".equals(rs.getString("status"))
                    && rs.getObject("deleted_at") == null
                    && rs.getBoolean("is_visible")
                    && rs.getBoolean("is_onboarded");
        }));
        if (!eligible) throw new TargetIneligibleException();

        checkRoleIsolation(actorId, targetRole[0]);

        boolean hasApprovedPhoto = !jdbc.queryForList(PRIMARY_PHOTO_CHECK_SQL, params).isEmpty();
        if (!hasApprovedPhoto) throw new TargetIneligibleException();

        var blockParams = new MapSqlParameterSource("actorId", actorId).addValue("targetId", targetId);
        boolean blocked = !jdbc.queryForList(BLOCK_CHECK_SQL, blockParams).isEmpty();
        if (blocked) throw new TargetIneligibleException();
    }

    private void checkBasicTargetEligibility(UUID actorId, UUID targetId) {
        var params = new MapSqlParameterSource("targetId", targetId);
        String[] targetRole = new String[1];
        boolean exists = Boolean.TRUE.equals(jdbc.query(TARGET_ELIGIBILITY_SQL, params, rs -> {
            if (!rs.next()) return false;
            targetRole[0] = rs.getString("role");
            return "ACTIVE".equals(rs.getString("status")) && rs.getObject("deleted_at") == null;
        }));
        if (!exists) throw new TargetIneligibleException();

        checkRoleIsolation(actorId, targetRole[0]);

        var blockParams = new MapSqlParameterSource("actorId", actorId).addValue("targetId", targetId);
        boolean blocked = !jdbc.queryForList(BLOCK_CHECK_SQL, blockParams).isEmpty();
        if (blocked) throw new TargetIneligibleException();
    }

    private void reverseExistingAction(DiscoveryActionRepository.ActionRow existing, UUID actorId) {
        actionRepo.reverseAction(existing.id());
        if ("LIKE".equals(existing.actionType()) || "SUPERLIKE".equals(existing.actionType())) {
            matchService.findActiveMatchByAction(existing.id()).ifPresent(match ->
                    matchLifecycleService.endMatch(match.id(), "CANCELLED_BY_ACTION_CHANGE", actorId));
        }
    }

    private SwipeActionResponse buildIdempotentResponse(DiscoveryActionRepository.ActionRow existing,
                                                         UUID actorId, String actionType) {
        Instant createdAt = existing.createdAt() != null ? existing.createdAt().toInstant() : Instant.now();
        MatchSummaryDto matchSummary = null;
        if ("LIKE".equals(actionType) || "SUPERLIKE".equals(actionType)) {
            matchSummary = matchService.findActiveMatchByAction(existing.id())
                    .map(mr -> matchService.buildMatchSummaryFromRow(mr, actorId))
                    .orElse(null);
        }
        return new SwipeActionResponse(
                existing.id(), existing.actionType(), "ACTIVE",
                matchSummary != null, matchSummary, null, null, null,
                createdAt, true
        );
    }

    private void acquirePairLock(UUID actorId, UUID targetId) {
        UUID lo = actorId.compareTo(targetId) < 0 ? actorId : targetId;
        UUID hi = actorId.compareTo(targetId) < 0 ? targetId : actorId;
        jdbc.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtext(:pairKey))",
                new MapSqlParameterSource("pairKey", lo.toString() + ":" + hi.toString()),
                Object.class);
    }

    private void checkRoleIsolation(UUID actorId, String targetRole) {
        String actorRole = jdbc.queryForObject(
                ACTOR_ROLE_SQL,
                new MapSqlParameterSource("actorId", actorId),
                String.class);
        if (actorRole == null) actorRole = "USER";
        boolean actorIsTest = "TEST".equals(actorRole);
        boolean targetIsTest = "TEST".equals(targetRole);
        if (actorIsTest != targetIsTest) {
            throw new TargetIneligibleException();
        }
    }
}
