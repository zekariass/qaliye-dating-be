package com.qaliye.backend.profile.dto;

import com.qaliye.backend.activity.ActivityStatus;
import com.qaliye.backend.catalog.EthnicityOption;
import com.qaliye.backend.catalog.LanguageOption;

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
        List<EthnicityOption> ethnicities,
        String ethnicityOtherText,
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
        List<LanguageOption> languages,
        Boolean isVerified,
        String primaryPhotoUrl,
        List<ProfilePhotoDto> photos,
        String relationStatus,
        UUID matchId,
        ActivityStatus activityStatus
) {}
