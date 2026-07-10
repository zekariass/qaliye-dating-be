package com.qaliye.backend.catalog;

import java.util.UUID;

public record EthnicityOption(
        UUID id,
        String code,
        String countryCode,
        String name,
        String region
) {}
