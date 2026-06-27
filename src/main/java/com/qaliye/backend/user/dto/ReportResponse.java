package com.qaliye.backend.user.dto;

import java.time.Instant;
import java.util.UUID;

public record ReportResponse(
        UUID id,
        UUID reportedUserId,
        String reportType,
        String description,
        String status,
        Instant createdAt
) {}
