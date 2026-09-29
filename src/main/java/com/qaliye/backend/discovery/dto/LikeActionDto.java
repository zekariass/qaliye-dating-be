package com.qaliye.backend.discovery.dto;

import java.time.Instant;

public record LikeActionDto(
        String code,
        String name,
        String description,
        String icon,
        boolean isDefault,
        long credits,
        int sortOrder,
        Integer limit,
        int used,
        Integer remaining,
        Instant resetsAt,
        String periodType,
        boolean blocked
) {}
