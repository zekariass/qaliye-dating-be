package com.qaliye.backend.discovery.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record SwipeActionRequest(
        @NotNull UUID targetUserId,
        @NotNull UUID clientActionId
) {}
