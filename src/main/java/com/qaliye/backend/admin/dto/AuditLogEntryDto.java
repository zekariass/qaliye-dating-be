package com.qaliye.backend.admin.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AuditLogEntryDto(
        UUID id,
        UUID actorUserId,
        String actorDisplayName,
        String action,
        String targetTable,
        UUID targetId,
        UUID requestId,
        String details,
        OffsetDateTime createdAt
) {}
