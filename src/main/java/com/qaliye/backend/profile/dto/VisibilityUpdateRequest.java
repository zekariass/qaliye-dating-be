package com.qaliye.backend.profile.dto;

import jakarta.validation.constraints.NotNull;

public record VisibilityUpdateRequest(
        @NotNull Boolean isVisible
) {}
