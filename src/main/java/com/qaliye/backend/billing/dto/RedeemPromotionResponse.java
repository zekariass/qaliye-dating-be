package com.qaliye.backend.billing.dto;

import java.time.Instant;
import java.util.UUID;

public record RedeemPromotionResponse(
        UUID redemptionId,
        UUID subscriptionId,
        String campaignKey,
        String planCode,
        Integer durationDays,
        Instant periodEnd,
        String message
) {}
