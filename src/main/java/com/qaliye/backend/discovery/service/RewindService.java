package com.qaliye.backend.discovery.service;

import com.qaliye.backend.discovery.dto.DiscoveryProfileDto;
import com.qaliye.backend.discovery.dto.RewindResponse;
import com.qaliye.backend.discovery.dto.UserPlanEntitlement;
import com.qaliye.backend.discovery.exception.DailyLimitExceededException;
import com.qaliye.backend.discovery.exception.NoRewindableActionException;
import com.qaliye.backend.discovery.exception.RewindMatchGracePeriodExpiredException;
import com.qaliye.backend.discovery.exception.RewindMatchHasMessagesException;
import com.qaliye.backend.discovery.repository.DailyLimitRepository;
import com.qaliye.backend.discovery.repository.DiscoveryActionRepository;
import com.qaliye.backend.discovery.repository.EntitlementLedgerRepository;
import com.qaliye.backend.discovery.repository.DiscoveryMatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class RewindService {

    private final DiscoveryActionRepository actionRepo;
    private final DailyLimitRepository dailyLimitRepo;
    private final EntitlementLedgerRepository ledgerRepo;
    private final MatchService matchService;
    private final PlanEntitlementService entitlementService;
    private final DiscoveryQueryService queryService;

    public RewindService(DiscoveryActionRepository actionRepo,
                         DailyLimitRepository dailyLimitRepo,
                         EntitlementLedgerRepository ledgerRepo,
                         MatchService matchService,
                         PlanEntitlementService entitlementService,
                         DiscoveryQueryService queryService) {
        this.actionRepo = actionRepo;
        this.dailyLimitRepo = dailyLimitRepo;
        this.ledgerRepo = ledgerRepo;
        this.matchService = matchService;
        this.entitlementService = entitlementService;
        this.queryService = queryService;
    }

    @Transactional
    public RewindResponse rewind(UUID actorId) {
        UserPlanEntitlement ent = entitlementService.loadEntitlement(actorId);
        dailyLimitRepo.ensureRowExists(actorId);
        DailyLimitRepository.DailyLimitRow limits = dailyLimitRepo.lockForUpdate(actorId)
                .orElseThrow(() -> new IllegalStateException("Daily limits row missing after upsert"));

        boolean usedCredit = false;
        if (ent.dailyRewindsLimit() != null && limits.rewindsUsed() >= ent.dailyRewindsLimit()) {
            if (ent.rewindCredits() <= 0) {
                throw new DailyLimitExceededException("DAILY_REWINDS");
            }
            usedCredit = true;
        }

        DiscoveryActionRepository.ActionRow action = actionRepo.findLastRewindable(actorId)
                .orElseThrow(NoRewindableActionException::new);

        UUID matchId = null;
        boolean matchCancelled = false;

        if ("LIKE".equals(action.actionType()) || "SUPERLIKE".equals(action.actionType())) {
            Optional<DiscoveryMatchRepository.MatchRow> activeMatch = matchService.findActiveMatchByAction(action.id());
            if (activeMatch.isPresent()) {
                DiscoveryMatchRepository.MatchRow match = activeMatch.get();
                if (match.rewindEligibleUntil() != null
                        && match.rewindEligibleUntil().toInstant().isBefore(Instant.now())) {
                    throw new RewindMatchGracePeriodExpiredException();
                }
                if (match.firstMessageAt() != null) {
                    throw new RewindMatchHasMessagesException();
                }
                matchService.endMatch(match.id(), "CANCELLED_BY_REWIND", actorId);
                matchId = match.id();
                matchCancelled = true;
            }
        }

        actionRepo.reverseAction(action.id());
        dailyLimitRepo.incrementRewinds(actorId);

        if (usedCredit) {
            ledgerRepo.consumeCredit(actorId, "REWIND_CREDIT", action.id(), UUID.randomUUID());
        }

        int rewindsRemaining = ent.dailyRewindsLimit() == null
                ? Integer.MAX_VALUE
                : Math.max(0, ent.dailyRewindsLimit() - limits.rewindsUsed() - 1);

        DiscoveryQueryService.ActorContext ctx = queryService.loadActorContext(actorId);
        DiscoveryProfileDto restoredProfile = ctx != null
                ? loadRestoredProfile(actorId, action.targetUserId(), ctx)
                : null;

        return new RewindResponse(
                action.id(),
                action.actionType(),
                action.targetUserId(),
                matchCancelled,
                matchId,
                rewindsRemaining,
                restoredProfile,
                Instant.now()
        );
    }

    private DiscoveryProfileDto loadRestoredProfile(UUID actorId, UUID targetId,
                                                     DiscoveryQueryService.ActorContext ctx) {
        try {
            return queryService.fetchSingleProfile(actorId, targetId, ctx, "NEARBY");
        } catch (Exception e) {
            return null;
        }
    }
}
