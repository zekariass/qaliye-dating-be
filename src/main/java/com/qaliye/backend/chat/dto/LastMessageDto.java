package com.qaliye.backend.chat.dto;

import java.time.Instant;
import java.util.UUID;

public record LastMessageDto(
        UUID id,
        long sequenceNumber,
        UUID senderUserId,
        String messageType,
        String preview,
        Instant createdAt
) {}
