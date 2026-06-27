package com.qaliye.backend.discovery;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record UpdatePreferencesRequest(
        @NotBlank String interestedInGender,
        @NotNull @Min(18) @Max(120) Integer minAge,
        @NotNull @Min(18) @Max(120) Integer maxAge,
        @NotNull @Min(1) @Max(500) Integer maxDistanceKm,
        List<String> preferredResidencyTypes,
        Boolean openToLongDistance,
        Boolean openToRelocation,
        Boolean showVerifiedOnly
) {}
