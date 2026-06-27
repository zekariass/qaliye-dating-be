package com.qaliye.backend.chat.dto;

import com.qaliye.backend.activity.ActivityStatus;

import java.util.UUID;

public record ParticipantDto(
        UUID userId,
        String displayName,
        String avatarUrl,
        boolean isVerified,
        ActivityStatus activityStatus
) {}
