package com.qaliye.backend.discovery.service;

import com.qaliye.backend.billing.repository.ActionLimitRepository;
import com.qaliye.backend.billing.service.ActionCostService;
import com.qaliye.backend.billing.service.CreditService;
import com.qaliye.backend.discovery.dto.DiscoveryProfileDto;
import com.qaliye.backend.discovery.dto.RewindResponse;
import com.qaliye.backend.discovery.exception.ActionLimitExceededException;
import com.qaliye.backend.discovery.exception.NoRewindableActionException;
import com.qaliye.backend.discovery.exception.RewindMatchGracePeriodExpiredException;
import com.qaliye.backend.discovery.exception.RewindMatchHasMessagesException;
import com.qaliye.backend.chat.service.MatchLifecycleService;
import com.qaliye.backend.discovery.repository.DiscoveryActionRepository;
import com.qaliye.backend.discovery.repository.DiscoveryMatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class RewindService {

    private final DiscoveryActionRepository actionRepo;
    private final ActionCostService actionCostService;
    private final ActionLimitRepository actionLimitRepo;
    private final CreditService creditService;
    private final MatchService matchService;
    private final DiscoveryQueryService queryService;
    private final MatchLifecycleService matchLifecycleService;

    public RewindService(DiscoveryActionRepository actionRepo,
                         ActionCostService actionCostService,
                         ActionLimitRepository actionLimitRepo,
                         CreditService creditService,
                         MatchService matchService,
                         DiscoveryQueryService queryService,
                         MatchLifecycleService matchLifecycleService) {
        this.actionRepo = actionRepo;
        this.actionCostService = actionCostService;
        this.actionLimitRepo = actionLimitRepo;
        this.creditService = creditService;
        this.matchService = matchService;
        this.queryService = queryService;
        this.matchLifecycleService = matchLifecycleService;
    }

    @Transactional
    public RewindResponse rewind(UUID actorId) {
        ActionCostService.ActionCostResult cost = actionCostService.evaluate(actorId, "REWIND");

        boolean limitExhausted = false;
        if (cost.ruleId() != null && cost.limitValue() != null) {
            actionLimitRepo.ensureExists(actorId, cost.ruleId(), cost.periodStart(), cost.periodEnd());
            boolean incremented = actionLimitRepo
                    .tryIncrementUnderLimit(actorId, cost.ruleId(), cost.periodStart(), cost.limitValue())
                    .isPresent();
            if (!incremented) {
                if (!cost.requiresCredits()) {
                    throw new ActionLimitExceededException("REWIND", cost.periodType());
                }
                limitExhausted = true;
            }
        } else if (cost.isBlocked()) {
            throw new ActionLimitExceededException("REWIND", cost.periodType());
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
                matchLifecycleService.endMatch(match.id(), "CANCELLED_BY_REWIND", actorId);
                matchId = match.id();
                matchCancelled = true;
            }
        }

        actionRepo.reverseAction(action.id());

        if (cost.requiresCredits()) {
            String idemKey = "rewind-" + action.id();
            creditService.consumeCredits(actorId, cost.creditCost(), "REWIND", idemKey);
        }

        int rewindsRemaining = cost.limitValue() == null
                ? Integer.MAX_VALUE
                : Math.max(0, cost.limitValue() - cost.currentUsedCount() - 1);

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
            return queryService.fetchSingleProfile(actorId, targetId, ctx);
        } catch (Exception e) {
            return null;
        }
    }
}
