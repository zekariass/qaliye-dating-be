package com.qaliye.backend.discovery.dto;

import java.util.UUID;

public record UserPlanEntitlement(
        UUID userId,
        String planCode,
        boolean isPaid,
        Integer dailyLikesLimit,
        Integer dailySuperLikesLimit,
        Integer dailyRewindsLimit,
        int superLikeCredits,
        int rewindCredits
) {}
