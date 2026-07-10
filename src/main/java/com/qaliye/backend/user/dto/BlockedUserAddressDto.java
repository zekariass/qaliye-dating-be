package com.qaliye.backend.user.dto;

import java.util.UUID;

public record BlockedUserAddressDto(
        UUID id,
        String countryCode,
        String countryName,
        String cityName
) {}
