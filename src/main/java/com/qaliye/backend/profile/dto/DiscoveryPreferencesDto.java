package com.qaliye.backend.profile.dto;

import java.util.List;

public record DiscoveryPreferencesDto(
        String interestedInGender,
        Integer minAge,
        Integer maxAge,
        Integer maxDistanceKm,
        List<String> preferredResidencyTypes,
        Boolean openToLongDistance,
        Boolean openToRelocation,
        Boolean showVerifiedOnly
) {}
