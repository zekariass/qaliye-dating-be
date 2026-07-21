package com.qaliye.backend.billing.dto;

import java.util.UUID;

public record ClaimablePromotionDto(
        UUID campaignId,
        String campaignKey,
        String name,
        String description,
        Integer durationDays,
        String endsAt
) {}
