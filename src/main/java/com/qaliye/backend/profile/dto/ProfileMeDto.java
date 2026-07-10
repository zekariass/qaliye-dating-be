package com.qaliye.backend.profile.dto;

import com.qaliye.backend.catalog.EthnicityOption;
import com.qaliye.backend.catalog.LanguageOption;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ProfileMeDto(
        UUID userId,
        String displayName,
        Integer age,
        String gender,
        LocalDate dateOfBirth,
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
        Boolean smoking,
        Boolean drinking,
        String smokingDetail,
        String drinkingDetail,
        String activityLevel,
        List<String> interests,
        List<LanguageOption> languages,
        Boolean isVisible,
        String discoveryMode,
        Boolean isOnboarded,
        Boolean isVerified,
        Integer profileCompletionScore,
        DiscoveryPreferencesDto discoveryPreferences,
        String primaryPhotoUrl,
        List<ProfilePhotoDto> photos
) {}
