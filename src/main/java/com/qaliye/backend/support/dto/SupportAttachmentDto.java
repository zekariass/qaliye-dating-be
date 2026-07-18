package com.qaliye.backend.support.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SupportAttachmentDto(
        UUID id,
        UUID messageId,
        String fileName,
        String contentType,
        long fileSizeBytes,
        String signedUrl,
        String attachmentKind,
        Long durationMs,
        OffsetDateTime createdAt
) {}
