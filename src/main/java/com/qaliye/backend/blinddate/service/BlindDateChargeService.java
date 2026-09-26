package com.qaliye.backend.blinddate.service;

import com.qaliye.backend.billing.repository.ActionLimitRepository;
import com.qaliye.backend.billing.service.ActionCostService;
import com.qaliye.backend.billing.service.CreditService;
import com.qaliye.backend.discovery.exception.ActionLimitExceededException;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Shared enforcement of a Blind Date paid action against the existing
 * subscription-plan limit / credit-cost infrastructure.
 *
 * <p>Mirrors the atomic ordering used by {@code SwipeService}: the usage tracker
 * is incremented under the limit first (TOCTOU-safe), and credits are only
 * consumed when the evaluation says they are required. The credit consumption is
 * keyed by a caller-supplied idempotency key so a retried request never double
 * charges.
 */
@Service
public class BlindDateChargeService {

    private final ActionCostService actionCostService;
    private final ActionLimitRepository actionLimitRepo;
    private final CreditService creditService;

    public BlindDateChargeService(ActionCostService actionCostService,
                                  ActionLimitRepository actionLimitRepo,
                                  CreditService creditService) {
        this.actionCostService = actionCostService;
        this.actionLimitRepo = actionLimitRepo;
        this.creditService = creditService;
    }

    /**
     * Enforces the configured limit and charges credits when required.
     *
     * @param userId         the actor paying for the action
     * @param actionCode     {@code BLIND_DATE_SESSION_CREATE} or {@code BLIND_DATE_PARTICIPATE}
     * @param idempotencyKey stable key for the logical charge; the same key never charges twice
     * @return the number of credits actually consumed (0 when covered by the plan allowance)
     */
    public long charge(UUID userId, String actionCode, String idempotencyKey) {
        ActionCostService.ActionCostResult cost = actionCostService.evaluate(userId, actionCode);

        if (cost.ruleId() != null && cost.limitValue() != null) {
            actionLimitRepo.ensureExists(userId, cost.ruleId(), cost.periodStart(), cost.periodEnd());
            boolean incremented = actionLimitRepo
                    .tryIncrementUnderLimit(userId, cost.ruleId(), cost.periodStart(), cost.limitValue())
                    .isPresent();
            if (!incremented && !cost.requiresCredits()) {
                throw new ActionLimitExceededException(actionCode, cost.periodType());
            }
        } else if (cost.isBlocked()) {
            throw new ActionLimitExceededException(actionCode, cost.periodType());
        }

        if (cost.requiresCredits()) {
            creditService.consumeCredits(userId, cost.creditCost(), actionCode, idempotencyKey);
            return cost.creditCost();
        }
        return 0L;
    }
}
