package com.qaliye.backend.billing.dto.admin;

import jakarta.validation.constraints.NotBlank;

public record CreateFeatureActionRequest(
        @NotBlank String code,
        @NotBlank String name,
        @NotBlank String type
) {}
