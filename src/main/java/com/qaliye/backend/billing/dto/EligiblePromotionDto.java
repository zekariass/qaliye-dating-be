package com.qaliye.backend.billing.dto;

import java.util.UUID;

public record EligiblePromotionDto(
        UUID campaignId,
        String campaignKey,
        String name,
        String description,
        String triggerType,
        String benefitType,
        String discountType,
        Long discountValue,
        String discountCurrency,
        UUID subscriptionProductId,
        Integer durationDays,
        Integer maxRedemptions,
        int reservedCount,
        int fulfilledCount,
        String endsAt,
        String targetGender,
        boolean canRedeem
) {}
