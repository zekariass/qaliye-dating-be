package com.qaliye.backend.discovery.dto;

import java.time.Instant;
import java.util.UUID;

public record MatchSummaryDto(
        UUID matchId,
        String matchSource,
        Instant matchedAt,
        Instant rewindEligibleUntil,
        MatchedUserSummaryDto otherUser
) {}
