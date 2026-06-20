package com.qaliye.backend.messaging;

import java.time.OffsetDateTime;
import java.util.UUID;

public record MessageDto(
        UUID id,
        UUID matchId,
        UUID senderUserId,
        String messageType,
        String body,
        String storageBucket,
        String storagePath,
        String moderationStatus,
        OffsetDateTime createdAt
) {
}
