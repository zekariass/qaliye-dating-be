package com.qaliye.backend.profile.dto;

import com.qaliye.backend.activity.ActivityStatus;

import java.util.List;
import java.util.UUID;

public record OtherUserProfileDto(
        UUID userId,
        String displayName,
        Integer age,
        String gender,
        String bio,
        Integer heightCm,
        String residencyType,
        ProfileAddressDto address,
        String ethnicity,
        String nationality,
        String religion,
        String educationLevel,
        String occupation,
        String relationshipIntention,
        String maritalStatus,
        Boolean hasChildren,
        Boolean wantsChildren,
        String activityLevel,
        List<String> interests,
        List<String> languages,
        Boolean isVerified,
        String primaryPhotoUrl,
        List<ProfilePhotoDto> photos,
        String relationStatus,
        UUID matchId,
        ActivityStatus activityStatus
) {}
