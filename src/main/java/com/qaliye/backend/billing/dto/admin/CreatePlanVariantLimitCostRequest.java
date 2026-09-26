package com.qaliye.backend.billing.dto.admin;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreatePlanVariantLimitCostRequest(
        @NotNull UUID subscriptionPlanId,
        @NotNull UUID actionFeatureVariantId,
        long memberCreditCost,
        long actualCreditCost,
        Integer limitValue,
        String periodType,
        Boolean applyCreditAfterLimit
) {}
