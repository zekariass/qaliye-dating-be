package com.qaliye.backend.chat.dto;

import java.util.List;

public record InboxResponse(
        List<InboxItemDto> items,
        String nextCursor
) {}
