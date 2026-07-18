package com.qaliye.backend.support.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record SupportMessageDto(
        UUID id,
        UUID conversationId,
        long sequenceNumber,
        String senderType,
        String senderDisplayName,
        String body,
        OffsetDateTime createdAt,
        List<SupportAttachmentDto> attachments
) {}
