package com.qaliye.backend.support.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record StaffConversationDetailDto(
        UUID id,
        UUID userId,
        String userDisplayName,
        String status,
        int priority,
        UUID assignedStaffUserId,
        long nextPublicSequence,
        long userLastReadSequence,
        long staffLastReadSequence,
        long myLastReadSequence,
        OffsetDateTime waitingSince,
        OffsetDateTime firstStaffResponseAt,
        OffsetDateTime lastActivityAt,
        OffsetDateTime lastPublicMessageAt,
        String lastPublicMessageSenderType,
        OffsetDateTime closedAt,
        String closedByType,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
