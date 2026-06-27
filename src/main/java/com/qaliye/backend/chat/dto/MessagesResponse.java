package com.qaliye.backend.chat.dto;

import com.qaliye.backend.activity.ActivityStatus;

import java.util.List;
import java.util.UUID;

public record MessagesResponse(
        UUID matchId,
        ActivityStatus participantActivityStatus,
        List<ChatMessageDto> items,
        boolean hasMore
) {}
