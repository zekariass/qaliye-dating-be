package com.qaliye.backend.user.dto;

import java.time.Instant;
import java.util.UUID;

public record BlockItemDto(
        UUID id,
        Instant blockedAt,
        String reason,
        BlockedUserDto blockedUser
) {}
