package com.qaliye.backend.profile.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record PhotoReorderItem(
        @NotNull UUID id,
        @NotNull Integer photoOrder,
        @NotNull Boolean isPrimary
) {}
