package com.qaliye.backend.billing.dto;

import jakarta.validation.constraints.NotBlank;

public record AdminDeclineRequest(
        @NotBlank String decisionNote
) {}
