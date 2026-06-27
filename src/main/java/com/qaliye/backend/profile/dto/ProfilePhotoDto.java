package com.qaliye.backend.profile.dto;

import java.time.Instant;
import java.util.UUID;

public record ProfilePhotoDto(
        UUID id,
        Integer photoOrder,
        Boolean isPrimary,
        String signedUrl,
        Instant expiresAt,
        String moderationStatus
) {}
