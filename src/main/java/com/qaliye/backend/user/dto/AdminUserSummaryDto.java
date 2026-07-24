package com.qaliye.backend.user.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminUserSummaryDto(
        UUID id,
        String displayName,
        String status,
        String role,
        String preferredLanguage,
        boolean isOnboarded,
        boolean isVerified,
        int profileCompletionScore,
        OffsetDateTime lastActiveAt,
        OffsetDateTime createdAt
) {}
