package com.qaliye.backend.support.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record PriorityRequest(
        @NotNull @Min(1) @Max(5) Integer priority
) {}
