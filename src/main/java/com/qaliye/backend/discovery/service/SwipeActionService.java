package com.qaliye.backend.discovery.service;

import com.qaliye.backend.discovery.dto.MatchSummaryDto;
import com.qaliye.backend.discovery.dto.SwipeActionResponse;
import com.qaliye.backend.discovery.dto.UserPlanEntitlement;
import com.qaliye.backend.discovery.exception.DailyLimitExceededException;
import com.qaliye.backend.discovery.exception.DuplicateActiveActionException;
import com.qaliye.backend.discovery.exception.TargetIneligibleException;
import com.qaliye.backend.discovery.repository.DailyLimitRepository;
import com.qaliye.backend.discovery.repository.DiscoveryActionRepository;
import com.qaliye.backend.discovery.repository.EntitlementLedgerRepository;
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
    private final DailyLimitRepository dailyLimitRepo;
    private final EntitlementLedgerRepository ledgerRepo;
    private final MatchService matchService;
    private final PlanEntitlementService entitlementService;
    private final NotificationDispatcher notificationDispatcher;
    private final NamedParameterJdbcTemplate jdbc;

    public SwipeActionService(DiscoveryActionRepository actionRepo,
                               DailyLimitRepository dailyLimitRepo,
                               EntitlementLedgerRepository ledgerRepo,
                               MatchService matchService,
                               PlanEntitlementService entitlementService,
                               NotificationDispatcher notificationDispatcher,
                               NamedParameterJdbcTemplate jdbc) {
        this.actionRepo = actionRepo;
        this.dailyLimitRepo = dailyLimitRepo;
        this.ledgerRepo = ledgerRepo;
        this.matchService = matchService;
        this.entitlementService = entitlementService;
        this.notificationDispatcher = notificationDispatcher;
        this.jdbc = jdbc;
    }

    private static final String TARGET_ELIGIBILITY_SQL = """
            SELECT au.status, au.deleted_at, p.is_visible, p.is_onboarded
            FROM app_users au
            JOIN profiles p ON p.user_id = au.id
            WHERE au.id = :targetId
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

        if (actionRepo.findActiveByPair(actorId, targetId).isPresent()) {
            throw new DuplicateActiveActionException();
        }

        UserPlanEntitlement ent = entitlementService.loadEntitlement(actorId);
        dailyLimitRepo.ensureRowExists(actorId);
        DailyLimitRepository.DailyLimitRow limits = dailyLimitRepo.lockForUpdate(actorId)
                .orElseThrow(() -> new IllegalStateException("Daily limits row missing after upsert"));

        if (ent.dailyLikesLimit() != null && limits.likesUsed() >= ent.dailyLikesLimit()) {
            throw new DailyLimitExceededException("DAILY_LIKES");
        }

        DiscoveryActionRepository.ActionRow action =
                actionRepo.insertAction(actorId, targetId, "LIKE", clientActionId);
        dailyLimitRepo.incrementLikes(actorId);

        Optional<DiscoveryActionRepository.ActionRow> mutualAction =
                actionRepo.findMutualActiveLike(actorId, targetId);
        Optional<MatchSummaryDto> match = Optional.empty();
        if (mutualAction.isPresent()) {
            match = matchService.tryCreateMatch(actorId, targetId, action.id(), mutualAction.get().id());
            match.ifPresent(m ->
                    notificationDispatcher.dispatchMatchNotification(actorId, targetId, m.matchId()));
        }

        int likesRemaining = ent.dailyLikesLimit() == null
                ? Integer.MAX_VALUE
                : Math.max(0, ent.dailyLikesLimit() - limits.likesUsed() - 1);

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

        if (actionRepo.findActiveByPair(actorId, targetId).isPresent()) {
            throw new DuplicateActiveActionException();
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

        if (actionRepo.findActiveByPair(actorId, targetId).isPresent()) {
            throw new DuplicateActiveActionException();
        }

        UserPlanEntitlement ent = entitlementService.loadEntitlement(actorId);
        dailyLimitRepo.ensureRowExists(actorId);
        DailyLimitRepository.DailyLimitRow limits = dailyLimitRepo.lockForUpdate(actorId)
                .orElseThrow(() -> new IllegalStateException("Daily limits row missing after upsert"));

        boolean usedCredit = false;
        if (ent.dailySuperLikesLimit() != null && limits.superLikesUsed() >= ent.dailySuperLikesLimit()) {
            if (ent.superLikeCredits() <= 0) {
                throw new DailyLimitExceededException("DAILY_SUPERLIKES");
            }
            usedCredit = true;
        }

        DiscoveryActionRepository.ActionRow action =
                actionRepo.insertAction(actorId, targetId, "SUPERLIKE", clientActionId);

        if (!usedCredit) {
            dailyLimitRepo.incrementSuperLikes(actorId);
        }

        if (usedCredit) {
            ledgerRepo.consumeCredit(actorId, "SUPERLIKE_CREDIT", action.id(), UUID.randomUUID());
        }

        Optional<DiscoveryActionRepository.ActionRow> mutualAction =
                actionRepo.findMutualActiveLike(actorId, targetId);
        Optional<MatchSummaryDto> match = Optional.empty();
        if (mutualAction.isPresent()) {
            match = matchService.tryCreateMatch(actorId, targetId, action.id(), mutualAction.get().id());
            match.ifPresent(m ->
                    notificationDispatcher.dispatchMatchNotification(actorId, targetId, m.matchId()));
        }

        int superLikesRemaining = ent.dailySuperLikesLimit() == null
                ? Integer.MAX_VALUE
                : Math.max(0, ent.dailySuperLikesLimit() - limits.superLikesUsed() - (usedCredit ? 0 : 1));
        int creditsRemaining = ent.superLikeCredits() - (usedCredit ? 1 : 0);

        Instant createdAt = action.createdAt() != null ? action.createdAt().toInstant() : Instant.now();
        return new SwipeActionResponse(
                action.id(), "SUPERLIKE", "ACTIVE",
                match.isPresent(), match.orElse(null),
                null, superLikesRemaining, creditsRemaining,
                createdAt, false
        );
    }

    private void checkTargetEligibility(UUID actorId, UUID targetId) {
        var params = new MapSqlParameterSource("targetId", targetId);
        boolean eligible = Boolean.TRUE.equals(jdbc.query(TARGET_ELIGIBILITY_SQL, params, rs -> {
            if (!rs.next()) return false;
            return "ACTIVE".equals(rs.getString("status"))
                    && rs.getObject("deleted_at") == null
                    && rs.getBoolean("is_visible")
                    && rs.getBoolean("is_onboarded");
        }));
        if (!eligible) throw new TargetIneligibleException();

        boolean hasApprovedPhoto = !jdbc.queryForList(PRIMARY_PHOTO_CHECK_SQL, params).isEmpty();
        if (!hasApprovedPhoto) throw new TargetIneligibleException();

        var blockParams = new MapSqlParameterSource("actorId", actorId).addValue("targetId", targetId);
        boolean blocked = !jdbc.queryForList(BLOCK_CHECK_SQL, blockParams).isEmpty();
        if (blocked) throw new TargetIneligibleException();
    }

    private void checkBasicTargetEligibility(UUID actorId, UUID targetId) {
        var params = new MapSqlParameterSource("targetId", targetId);
        boolean exists = Boolean.TRUE.equals(jdbc.query(TARGET_ELIGIBILITY_SQL, params, rs -> {
            if (!rs.next()) return false;
            return "ACTIVE".equals(rs.getString("status")) && rs.getObject("deleted_at") == null;
        }));
        if (!exists) throw new TargetIneligibleException();

        var blockParams = new MapSqlParameterSource("actorId", actorId).addValue("targetId", targetId);
        boolean blocked = !jdbc.queryForList(BLOCK_CHECK_SQL, blockParams).isEmpty();
        if (blocked) throw new TargetIneligibleException();
    }

    private SwipeActionResponse buildIdempotentResponse(DiscoveryActionRepository.ActionRow existing,
                                                         UUID actorId, String actionType) {
        Instant createdAt = existing.createdAt() != null ? existing.createdAt().toInstant() : Instant.now();
        return new SwipeActionResponse(
                existing.id(), existing.actionType(), "ACTIVE",
                false, null, null, null, null,
                createdAt, true
        );
    }
}
