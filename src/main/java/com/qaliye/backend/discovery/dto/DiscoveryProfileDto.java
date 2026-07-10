package com.qaliye.backend.discovery.dto;

import com.qaliye.backend.activity.ActivityStatus;
import com.qaliye.backend.catalog.EthnicityOption;
import com.qaliye.backend.catalog.LanguageOption;

import java.util.List;
import java.util.UUID;

public record DiscoveryProfileDto(
        UUID userId,
        String displayName,
        int age,
        String gender,
        String bio,
        String residencyType,
        String city,
        String region,
        String countryName,
        Integer distanceKm,
        boolean isVerified,
        String relationshipIntention,
        Integer heightCm,
        List<EthnicityOption> ethnicities,
        String nationality,
        String religion,
        String educationLevel,
        String occupation,
        String maritalStatus,
        Boolean hasChildren,
        Boolean wantsChildren,
        String smoking,
        String drinking,
        List<LanguageOption> languages,
        List<DiscoveryPhotoDto> photos,
        List<DiscoveryPromptAnswerDto> promptAnswers,
        boolean isBoosted,
        double discoveryScore,
        ActivityStatus activityStatus
) {
    public DiscoveryProfileDto withPhotos(List<DiscoveryPhotoDto> photos) {
        return new DiscoveryProfileDto(userId, displayName, age, gender, bio, residencyType,
                city, region, countryName, distanceKm, isVerified, relationshipIntention,
                heightCm, ethnicities, nationality, religion, educationLevel, occupation,
                maritalStatus, hasChildren, wantsChildren, smoking, drinking,
                languages, photos, promptAnswers, isBoosted, discoveryScore, activityStatus);
    }

    public DiscoveryProfileDto withPromptAnswers(List<DiscoveryPromptAnswerDto> answers) {
        return new DiscoveryProfileDto(userId, displayName, age, gender, bio, residencyType,
                city, region, countryName, distanceKm, isVerified, relationshipIntention,
                heightCm, ethnicities, nationality, religion, educationLevel, occupation,
                maritalStatus, hasChildren, wantsChildren, smoking, drinking,
                languages, photos, answers, isBoosted, discoveryScore, activityStatus);
    }

    public DiscoveryProfileDto withCatalogData(List<EthnicityOption> eths, List<LanguageOption> langs) {
        return new DiscoveryProfileDto(userId, displayName, age, gender, bio, residencyType,
                city, region, countryName, distanceKm, isVerified, relationshipIntention,
                heightCm, eths, nationality, religion, educationLevel, occupation,
                maritalStatus, hasChildren, wantsChildren, smoking, drinking,
                langs, photos, promptAnswers, isBoosted, discoveryScore, activityStatus);
    }
}
