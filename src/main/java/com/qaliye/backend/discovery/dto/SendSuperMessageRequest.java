package com.qaliye.backend.discovery.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record SendSuperMessageRequest(
        @NotNull UUID targetUserId,
        @NotBlank @Size(max = 500) String message,
        UUID idempotencyKey
) {}
