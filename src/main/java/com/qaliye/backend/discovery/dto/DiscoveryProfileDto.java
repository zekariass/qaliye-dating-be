package com.qaliye.backend.discovery.dto;

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
        String ethnicity,
        String nationality,
        String religion,
        String educationLevel,
        String occupation,
        String maritalStatus,
        boolean hasChildren,
        Boolean wantsChildren,
        boolean smoking,
        boolean drinking,
        List<DiscoveryPhotoDto> photos,
        List<DiscoveryPromptAnswerDto> promptAnswers,
        boolean isBoosted,
        double discoveryScore
) {
    public DiscoveryProfileDto withPhotos(List<DiscoveryPhotoDto> photos) {
        return new DiscoveryProfileDto(userId, displayName, age, gender, bio, residencyType,
                city, region, countryName, distanceKm, isVerified, relationshipIntention,
                heightCm, ethnicity, nationality, religion, educationLevel, occupation,
                maritalStatus, hasChildren, wantsChildren, smoking, drinking,
                photos, promptAnswers, isBoosted, discoveryScore);
    }

    public DiscoveryProfileDto withPromptAnswers(List<DiscoveryPromptAnswerDto> answers) {
        return new DiscoveryProfileDto(userId, displayName, age, gender, bio, residencyType,
                city, region, countryName, distanceKm, isVerified, relationshipIntention,
                heightCm, ethnicity, nationality, religion, educationLevel, occupation,
                maritalStatus, hasChildren, wantsChildren, smoking, drinking,
                photos, answers, isBoosted, discoveryScore);
    }
}
