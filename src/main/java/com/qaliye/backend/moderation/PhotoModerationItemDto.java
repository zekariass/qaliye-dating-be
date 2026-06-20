package com.qaliye.backend.moderation;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PhotoModerationItemDto(
        UUID id,
        UUID userId,
        String imageUrl,
        String moderationStatus,
        OffsetDateTime createdAt,
        String displayName
) {
}
