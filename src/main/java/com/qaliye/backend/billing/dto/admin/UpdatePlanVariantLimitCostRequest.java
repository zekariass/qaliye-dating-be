package com.qaliye.backend.billing.dto.admin;

import java.util.UUID;

public record UpdatePlanVariantLimitCostRequest(
        UUID subscriptionPlanId,
        UUID actionFeatureVariantId,
        Long memberCreditCost,
        Long actualCreditCost,
        Integer limitValue,
        String periodType,
        Boolean applyCreditAfterLimit
) {}
