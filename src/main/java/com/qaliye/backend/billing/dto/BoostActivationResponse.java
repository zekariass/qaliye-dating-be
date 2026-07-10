package com.qaliye.backend.billing.dto;

import java.time.Instant;
import java.util.UUID;

public record BoostActivationResponse(
        UUID boostId,
        Instant startedAt,
        Instant expiresAt,
        int creditsRemaining
) {}
