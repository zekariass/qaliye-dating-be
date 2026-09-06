package com.qaliye.backend.support.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SupportConversationDto(
        UUID id,
        String status,
        long userLastReadSequence,
        long nextPublicSequence,
        long unreadCount,
        OffsetDateTime lastPublicMessageAt,
        String lastPublicMessageSenderType,
        OffsetDateTime closedAt,
        OffsetDateTime createdAt
) {}
