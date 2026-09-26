package com.qaliye.backend.discovery.service;

import com.qaliye.backend.billing.repository.ActionFeatureVariantRepository;
import com.qaliye.backend.billing.service.ActionCostService;
import com.qaliye.backend.discovery.dto.LikeActionDto;
import com.qaliye.backend.discovery.dto.LikeActionsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Resolves the currently available LIKE variants (HEART, ROSE, BUNA, ...) and their
 * effective credit cost for the authenticated user's subscription plan.
 */
@Service
public class LikeVariantService {

    private static final Logger log = LoggerFactory.getLogger(LikeVariantService.class);

    private final ActionFeatureVariantRepository variantRepo;
    private final ActionCostService actionCostService;

    public LikeVariantService(ActionFeatureVariantRepository variantRepo,
                              ActionCostService actionCostService) {
        this.variantRepo = variantRepo;
        this.actionCostService = actionCostService;
    }

    @Transactional(readOnly = true)
    public LikeActionsResponse getAvailableLikeActions(UUID actorId) {
        List<ActionFeatureVariantRepository.VariantRow> variants = variantRepo.findActiveByActionCode("LIKE");

        List<LikeActionDto> actions = new ArrayList<>();
        for (ActionFeatureVariantRepository.VariantRow variant : variants) {
            ActionCostService.VariantCostResult cost;
            try {
                cost = actionCostService.evaluateVariant(actorId, "LIKE", variant.id());
            } catch (ActionCostService.VariantPricingNotConfiguredException e) {
                log.warn("Skipping LIKE variant {} for user {}: {}", variant.code(), actorId, e.getMessage());
                continue;
            }
            actions.add(new LikeActionDto(
                    variant.code(),
                    variant.name(),
                    variant.description(),
                    variant.icon(),
                    cost.creditCost(),
                    variant.sortOrder(),
                    cost.limitValue(),
                    cost.currentUsedCount(),
                    cost.limitValue() != null ? Math.max(0, cost.limitValue() - cost.currentUsedCount()) : null,
                    resolveResetsAt(cost.periodType(), cost.periodEnd()),
                    cost.periodType(),
                    cost.actionBlocked()
            ));
        }
        return new LikeActionsResponse(actions);
    }

    /**
     * Maps the tracking period end to the instant the allowance resets.
     * LIFETIME periods never reset, so {@code null} is returned.
     */
    private Instant resolveResetsAt(String periodType, LocalDate periodEnd) {
        if ("LIFETIME".equals(periodType) || periodEnd == null) {
            return null;
        }
        return periodEnd.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
