package com.qaliye.backend.profile.dto;

public record ProfileLocationDto(
        String locationSource,
        String displayName,
        String city,
        String region,
        String countryCode,
        String countryName,
        String formattedAddress,
        String placeId,
        String locationPrecision
) {}
