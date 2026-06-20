package com.qaliye.backend.discovery.dto;

import java.util.UUID;

public record RewindResponse(
        UUID reversedActionId,
        String reversedActionType,
        UUID reversedTargetUserId,
        boolean matchCancelled,
        UUID matchId,
        int dailyRewindsRemaining,
        DiscoveryProfileDto restoredProfile,
        java.time.Instant reversedAt
) {}
