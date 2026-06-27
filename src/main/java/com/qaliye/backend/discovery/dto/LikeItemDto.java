package com.qaliye.backend.discovery.dto;

import com.qaliye.backend.activity.ActivityStatus;

import java.time.Instant;
import java.util.UUID;

public record LikeItemDto(
        UUID actionId,
        UUID userId,
        String displayName,
        int age,
        boolean isVerified,
        String primaryPhotoUrl,
        String actionType,
        Instant likedAt,
        Integer distanceKm,
        String city,
        String region,
        String countryName,
        ActivityStatus activityStatus
) {}
