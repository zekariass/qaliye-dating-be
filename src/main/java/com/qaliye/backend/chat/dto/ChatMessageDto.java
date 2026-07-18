package com.qaliye.backend.chat.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ChatMessageDto(
        UUID id,
        UUID matchId,
        long sequenceNumber,
        UUID senderUserId,
        String messageType,
        String body,
        String deliveryStatus,
        Instant createdAt,
        List<ChatAttachmentDto> attachments
) {}
