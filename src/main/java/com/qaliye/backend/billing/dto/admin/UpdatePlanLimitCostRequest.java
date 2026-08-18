package com.qaliye.backend.billing.dto.admin;

import java.util.UUID;

public record UpdatePlanLimitCostRequest(
        UUID subscriptionPlanId,
        UUID featureActionId,
        Long memberCreditCost,
        Long actualCreditCost,
        Integer limitValue,
        String periodType,
        Boolean applyCreditAfterLimit
) {}
