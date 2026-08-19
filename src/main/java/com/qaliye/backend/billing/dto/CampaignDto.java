package com.qaliye.backend.billing.dto;

import java.time.Instant;
import java.util.UUID;

public record CampaignDto(
        UUID id,
        String campaignKey,
        String name,
        String description,
        String triggerType,
        String eligibilityType,
        String benefitType,
        String discountType,
        Long discountValue,
        String discountCurrency,
        UUID subscriptionProductId,
        UUID consumableProductId,
        String countryCode,
        Integer durationDays,
        Integer newUserWindowDays,
        Integer maxRedemptions,
        int maxRedemptionsPerUser,
        int reservedCount,
        int fulfilledCount,
        int priority,
        Instant startsAt,
        Instant endsAt,
        String status,
        String targetGender,
        Instant createdAt,
        Instant updatedAt
) {}
