package com.qaliye.backend.billing.dto;

import java.time.Instant;
import java.util.UUID;

public record UserRedemptionDto(
        UUID id,
        UUID campaignId,
        String campaignKey,
        String campaignName,
        String benefitType,
        Integer durationDays,
        UUID subscriptionId,
        UUID paymentOrderId,
        String status,
        Long originalAmountMinor,
        Long discountAmountMinor,
        Long finalAmountMinor,
        String currency,
        Instant reservedAt,
        Instant fulfilledAt,
        Instant cancelledAt,
        Instant expiredAt,
        String failureCode,
        String subscriptionStatus,
        Instant subscriptionPeriodEnd
) {}
