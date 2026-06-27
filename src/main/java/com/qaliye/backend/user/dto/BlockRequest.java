package com.qaliye.backend.user.dto;

import jakarta.validation.constraints.Size;

public record BlockRequest(
        @Size(max = 500) String reason
) {}
