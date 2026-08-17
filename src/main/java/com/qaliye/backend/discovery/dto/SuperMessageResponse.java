package com.qaliye.backend.discovery.dto;

import java.time.Instant;
import java.util.UUID;

public record SuperMessageResponse(
        UUID id,
        UUID senderId,
        UUID receiverId,
        UserProfileBrief sender,
        UserProfileBrief receiver,
        String message,
        String actionType,
        long creditCost,
        String status,
        Instant viewedAt,
        Instant respondedAt,
        UUID matchId,
        UUID discoveryActionId,
        Instant createdAt
) {}
