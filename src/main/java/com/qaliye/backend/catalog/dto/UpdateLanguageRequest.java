package com.qaliye.backend.catalog.dto;

import jakarta.validation.constraints.Size;

public record UpdateLanguageRequest(
        @Size(max = 100) String name,
        @Size(max = 100) String nativeName,
        Boolean isActive,
        Integer sortOrder
) {}
