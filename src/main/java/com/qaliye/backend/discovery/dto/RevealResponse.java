package com.qaliye.backend.discovery.dto;

import java.util.UUID;

public record RevealResponse(
        UUID actionId,
        String actionType,
        UUID actorUserId,
        String actorDisplayName,
        Integer actorAge,
        String actorPrimaryPhotoUrl,
        boolean idempotent,
        long creditBalance
) {}
