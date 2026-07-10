package com.qaliye.backend.catalog.dto;

import jakarta.validation.constraints.Size;

public record UpdateEthnicityRequest(
        @Size(max = 100) String name,
        @Size(max = 100) String region,
        Boolean isActive,
        Integer sortOrder
) {}
