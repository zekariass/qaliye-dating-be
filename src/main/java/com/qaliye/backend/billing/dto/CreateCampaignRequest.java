package com.qaliye.backend.billing.dto;

import java.time.Instant;
import java.util.UUID;

public record CreateCampaignRequest(
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
        Integer maxRedemptionsPerUser,
        Integer priority,
        Instant startsAt,
        Instant endsAt,
        String targetGender
) {}
