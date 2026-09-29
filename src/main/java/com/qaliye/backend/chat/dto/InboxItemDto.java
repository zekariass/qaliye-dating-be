package com.qaliye.backend.chat.dto;

import java.time.Instant;
import java.util.UUID;

public record InboxItemDto(
        UUID matchId,
        String status,
        String matchSource,
        ParticipantDto participant,
        LastMessageDto lastMessage,
        int unreadCount,
        Instant mutedUntil,
        Instant matchedAt,
        Instant lastMessageAt
) {}
