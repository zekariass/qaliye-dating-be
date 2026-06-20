package com.qaliye.backend.discovery.dto;

import java.time.Instant;
import java.util.UUID;

public record DiscoveryPhotoDto(
        UUID id,
        int photoOrder,
        boolean isPrimary,
        String signedUrl,
        Instant expiresAt
) {}
