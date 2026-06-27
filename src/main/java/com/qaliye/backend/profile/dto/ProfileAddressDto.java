package com.qaliye.backend.profile.dto;

import java.util.UUID;

public record ProfileAddressDto(
        UUID id,
        String city,
        String region,
        String countryCode,
        String countryName,
        String formattedAddress,
        String locationSource
) {}
