package com.qaliye.backend.support.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record MarkReadRequest(
        @NotNull @Min(0) Long lastReadSequence
) {}
