package com.qaliye.backend.discovery.dto;

import com.qaliye.backend.activity.ActivityStatus;

import java.time.Instant;
import java.util.UUID;

public record MatchItemDto(
        UUID matchId,
        UUID userId,
        String displayName,
        int age,
        boolean isVerified,
        String primaryPhotoUrl,
        Instant matchedAt,
        Instant rewindEligibleUntil,
        Instant firstMessageAt,
        Instant lastMessageAt,
        boolean hasConversation,
        boolean isUnread,
        Integer distanceKm,
        String city,
        String region,
        String countryName,
        ActivityStatus activityStatus
) {}
