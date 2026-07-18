package com.qaliye.backend.support.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record SendSupportMessageRequest(
        @NotNull UUID clientMessageId,
        String body
) {}
