package com.qaliye.backend.support.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record StaffConversationSummaryDto(
        UUID id,
        UUID userId,
        String userDisplayName,
        String status,
        int priority,
        UUID assignedStaffUserId,
        long nextPublicSequence,
        long staffLastReadSequence,
        OffsetDateTime waitingSince,
        OffsetDateTime lastPublicMessageAt,
        String lastPublicMessageSenderType,
        OffsetDateTime createdAt
) {}
