package com.qaliye.backend.billing.dto;

import java.time.Instant;

public record UpdateCampaignRequest(
        String name,
        String description,
        Integer maxRedemptions,
        Integer maxRedemptionsPerUser,
        Integer priority,
        Instant endsAt,
        String targetGender
) {}
