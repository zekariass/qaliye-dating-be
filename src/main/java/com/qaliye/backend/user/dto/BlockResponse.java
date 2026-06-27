package com.qaliye.backend.user.dto;

import java.time.Instant;
import java.util.UUID;

public record BlockResponse(
        UUID id,
        UUID blockedUserId,
        String status,
        String reason,
        Instant blockedAt
) {}
