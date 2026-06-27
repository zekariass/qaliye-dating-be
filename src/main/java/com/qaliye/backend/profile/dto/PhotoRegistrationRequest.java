package com.qaliye.backend.profile.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PhotoRegistrationRequest(
        @NotBlank String storageBucket,
        @NotBlank String storagePath,
        @Min(0) @Max(8) Integer photoOrder,
        @NotNull Boolean isPrimary
) {}
