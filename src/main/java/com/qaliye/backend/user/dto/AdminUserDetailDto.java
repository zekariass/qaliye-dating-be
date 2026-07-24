package com.qaliye.backend.user.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminUserDetailDto(
        UUID id,
        String displayName,
        String status,
        String role,
        String preferredLanguage,
        String gender,
        String residencyType,
        String relationshipIntention,
        boolean isOnboarded,
        boolean isVerified,
        boolean isVisible,
        int profileCompletionScore,
        int photoCount,
        int pendingPhotoCount,
        int approvedPhotoCount,
        int rejectedPhotoCount,
        int manualReviewPhotoCount,
        int reportCount,
        int pendingReportCount,
        String verificationStatus,
        int activeMatchCount,
        OffsetDateTime lastActiveAt,
        OffsetDateTime deletedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
