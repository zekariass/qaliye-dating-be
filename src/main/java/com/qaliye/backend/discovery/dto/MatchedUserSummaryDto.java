package com.qaliye.backend.discovery.dto;

import java.util.UUID;

public record MatchedUserSummaryDto(
        UUID userId,
        String displayName,
        String primaryPhotoUrl
) {}
