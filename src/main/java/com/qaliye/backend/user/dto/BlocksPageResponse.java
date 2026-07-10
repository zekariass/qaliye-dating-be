package com.qaliye.backend.user.dto;

import java.util.List;

public record BlocksPageResponse(
        List<BlockItemDto> items,
        String nextCursor,
        boolean hasMore
) {}
