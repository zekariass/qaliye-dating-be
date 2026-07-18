package com.qaliye.backend.chat.dto;

import java.time.Instant;
import java.util.UUID;

public record ChatAttachmentDto(
        UUID id,
        UUID messageId,
        String attachmentType,
        String fileName,
        String contentType,
        long fileSizeBytes,
        Long durationMs,
        String downloadUrl,
        Instant createdAt
) {}
