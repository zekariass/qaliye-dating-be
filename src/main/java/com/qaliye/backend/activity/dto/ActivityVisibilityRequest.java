package com.qaliye.backend.activity.dto;

import jakarta.validation.constraints.NotNull;

public record ActivityVisibilityRequest(
        @NotNull Boolean showActivityStatus
) {}
