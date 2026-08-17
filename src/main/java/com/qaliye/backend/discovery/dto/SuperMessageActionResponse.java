package com.qaliye.backend.discovery.dto;

import java.time.Instant;
import java.util.UUID;

public record SuperMessageActionResponse(
        UUID messageId,
        String status,
        Instant respondedAt,
        boolean matched,
        UUID matchId,
        Instant matchedAt
) {}
