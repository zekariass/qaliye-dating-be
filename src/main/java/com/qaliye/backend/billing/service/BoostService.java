package com.qaliye.backend.billing.service;

import com.qaliye.backend.billing.BillingProperties;
import com.qaliye.backend.billing.dto.BoostActivationResponse;
import com.qaliye.backend.billing.repository.ActionLimitRepository;
import com.qaliye.backend.billing.repository.CreditLotRepository;
import com.qaliye.backend.discovery.exception.ActionLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BoostService {

    private static final Logger log = LoggerFactory.getLogger(BoostService.class);

    private final CreditLotRepository creditLotRepo;
    private final BillingProperties billingProps;
    private final NamedParameterJdbcTemplate jdbc;
    private final ActionCostService actionCostService;
    private final CreditService creditService;
    private final ActionLimitRepository actionLimitRepo;

    public BoostService(CreditLotRepository creditLotRepo, BillingProperties billingProps,
                        NamedParameterJdbcTemplate jdbc,
                        ActionCostService actionCostService,
                        CreditService creditService,
                        ActionLimitRepository actionLimitRepo) {
        this.creditLotRepo = creditLotRepo;
        this.billingProps = billingProps;
        this.jdbc = jdbc;
        this.actionCostService = actionCostService;
        this.creditService = creditService;
        this.actionLimitRepo = actionLimitRepo;
    }

    @Transactional
    public BoostActivationResponse activateBoost(UUID userId, String idempotencyKey) {
        // Reject if user is in incognito mode — boosting while hidden is pointless
        String discoveryMode = jdbc.queryForObject(
                "SELECT discovery_mode FROM profiles WHERE user_id = :userId",
                Map.of("userId", userId), String.class);
        if ("INCOGNITO".equals(discoveryMode)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "cannot_boost_while_incognito");
        }

        // Check for existing active boost
        List<CreditLotRepository.ActiveBoostRow> active = creditLotRepo.findActiveBoost(userId);
        if (!active.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "boost_already_active");
        }

        // Evaluate BOOST action cost from subscription_plan_limit_and_cost
        ActionCostService.ActionCostResult cost = actionCostService.evaluate(userId, "BOOST");

        if (cost.isBlocked()) {
            throw new ActionLimitExceededException("BOOST", cost.periodType());
        }

        String idemKey = idempotencyKey != null ? "boost-" + idempotencyKey : "boost-" + UUID.randomUUID();

        if (cost.ruleId() != null && cost.limitValue() != null) {
            // Limited allowance — try to consume one slot
            actionLimitRepo.ensureExists(userId, cost.ruleId(), cost.periodStart(), cost.periodEnd());
            boolean incremented = actionLimitRepo
                    .tryIncrementUnderLimit(userId, cost.ruleId(), cost.periodStart(), cost.limitValue())
                    .isPresent();
            if (!incremented && !cost.requiresCredits()) {
                throw new ActionLimitExceededException("BOOST", cost.periodType());
            }
        }

        if (cost.requiresCredits()) {
            creditService.consumeCredits(userId, cost.creditCost(), "BOOST", idemKey);
        }

        // Create the boost activation record (consumption_ledger_entry_id is nullable)
        CreditLotRepository.BoostInsertRow boost = creditLotRepo.insertBoost(
                userId, null, billingProps.getBoostDurationMinutes());

        int creditsRemaining = (int) Math.min(creditService.getBalance(userId), Integer.MAX_VALUE);
        log.info("Boost activated for user={}, boostId={}, expires={}",
                userId, boost.id(), boost.expiresAt());

        return new BoostActivationResponse(boost.id(), boost.startedAt(), boost.expiresAt(), creditsRemaining);
    }
}
