package com.qaliye.backend.discovery.dto;

public record RevisitPassesResponse(
        boolean success,
        int reopenedCount
) {}
