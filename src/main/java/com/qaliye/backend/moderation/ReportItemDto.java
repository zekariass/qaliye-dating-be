package com.qaliye.backend.moderation;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ReportItemDto(
        UUID id,
        UUID reporterUserId,
        UUID reportedUserId,
        String reportType,
        String description,
        UUID relatedMessageId,
        String status,
        OffsetDateTime createdAt,
        String reportedDisplayName
) {
}
