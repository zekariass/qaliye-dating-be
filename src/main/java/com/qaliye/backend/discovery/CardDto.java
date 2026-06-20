package com.qaliye.backend.discovery;

import java.util.List;
import java.util.UUID;

public record CardDto(
        UUID userId,
        String displayName,
        Integer age,
        String gender,
        String bio,
        String residencyType,
        String city,
        String countryCode,
        Double distanceKm,
        boolean isVerified,
        boolean isBoosted,
        int profileCompletionScore,
        String relationshipIntention,
        List<PhotoCardDto> photos,
        List<PromptAnswerDto> promptAnswers
) {
    public CardDto withPhotos(List<PhotoCardDto> photos) {
        return new CardDto(userId, displayName, age, gender, bio, residencyType,
                city, countryCode, distanceKm, isVerified, isBoosted,
                profileCompletionScore, relationshipIntention, photos, promptAnswers);
    }

    public CardDto withPromptAnswers(List<PromptAnswerDto> promptAnswers) {
        return new CardDto(userId, displayName, age, gender, bio, residencyType,
                city, countryCode, distanceKm, isVerified, isBoosted,
                profileCompletionScore, relationshipIntention, photos, promptAnswers);
    }
}
