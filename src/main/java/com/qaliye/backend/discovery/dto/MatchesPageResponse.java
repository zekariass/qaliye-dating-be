package com.qaliye.backend.discovery.dto;

import java.util.List;

public record MatchesPageResponse(
        List<MatchItemDto> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious
) {}
