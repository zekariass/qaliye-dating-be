package com.qaliye.backend.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateLanguageRequest(
        @NotBlank @Pattern(regexp = "[a-z][a-z0-9_-]*") String code,
        @NotBlank @Pattern(regexp = "[A-Z]{2}") @Size(min = 2, max = 2) String countryCode,
        @NotBlank @Size(max = 100) String name,
        @Size(max = 100) String nativeName,
        int sortOrder
) {}
