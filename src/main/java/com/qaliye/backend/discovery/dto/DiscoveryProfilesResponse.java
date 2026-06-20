package com.qaliye.backend.discovery.dto;

import java.util.List;

public record DiscoveryProfilesResponse(
        List<DiscoveryProfileDto> profiles,
        String nextCursor,
        boolean hasMore,
        int totalEligible,
        String locationFilter,
        int batchSize,
        boolean cursorReset
) {}
