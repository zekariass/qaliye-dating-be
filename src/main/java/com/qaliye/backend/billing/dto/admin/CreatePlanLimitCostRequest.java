package com.qaliye.backend.billing.dto.admin;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreatePlanLimitCostRequest(
        @NotNull UUID subscriptionPlanId,
        @NotNull UUID featureActionId,
        long memberCreditCost,
        long actualCreditCost,
        Integer limitValue,
        String periodType,
        Boolean applyCreditAfterLimit
) {}
