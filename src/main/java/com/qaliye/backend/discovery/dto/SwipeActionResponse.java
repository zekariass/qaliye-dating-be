package com.qaliye.backend.discovery.dto;

import java.util.UUID;

public record SwipeActionResponse(
        UUID actionId,
        String actionType,
        String status,
        boolean isMatch,
        MatchSummaryDto match,
        Integer dailyLikesRemaining,
        Integer dailySuperLikesRemaining,
        Integer superLikeCreditsRemaining,
        java.time.Instant createdAt,
        boolean idempotent
) {}
