package com.qaliye.backend.activity.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record BatchStatusRequest(
        @NotNull @NotEmpty @Size(min = 1, max = 50) List<@NotNull UUID> userIds
) {}
