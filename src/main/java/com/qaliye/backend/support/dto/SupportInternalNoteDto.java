package com.qaliye.backend.support.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SupportInternalNoteDto(
        UUID id,
        UUID conversationId,
        UUID staffUserId,
        String staffDisplayName,
        String body,
        OffsetDateTime createdAt
) {}
