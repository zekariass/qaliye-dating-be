package com.qaliye.backend.discovery.dto;

import java.util.List;

public record LikesPageResponse(
        List<LikeItemDto> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious,
        String direction
) {}
