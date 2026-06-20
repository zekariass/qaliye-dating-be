package com.qaliye.backend.profile;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record SetLocationRequest(
        @NotBlank String locationSource,
        // GPS fields
        Double latitude,
        Double longitude,
        String city,
        String countryCode,
        String countryName,
        String region,
        String formattedAddress,
        // MANUAL field
        UUID placeId
) {}
