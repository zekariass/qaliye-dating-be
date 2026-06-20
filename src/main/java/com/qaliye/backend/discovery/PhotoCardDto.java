package com.qaliye.backend.discovery;

import java.util.UUID;

public record PhotoCardDto(
        UUID id,
        int order,
        boolean isPrimary,
        String signedUrl
) {}
