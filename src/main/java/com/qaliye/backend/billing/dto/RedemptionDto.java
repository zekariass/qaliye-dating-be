package com.qaliye.backend.billing.dto;

import java.time.Instant;
import java.util.UUID;

public record RedemptionDto(
        UUID id,
        UUID campaignId,
        String campaignKey,
        UUID userId,
        UUID subscriptionId,
        UUID paymentOrderId,
        String status,
        String eligibilityCountry,
        String eligibilityGender,
        Long originalAmountMinor,
        Long discountAmountMinor,
        Long finalAmountMinor,
        String currency,
        Instant reservedAt,
        Instant fulfilledAt,
        Instant cancelledAt,
        Instant expiredAt,
        String failureCode
) {}
