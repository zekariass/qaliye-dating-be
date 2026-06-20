package com.qaliye.backend.discovery.dto;

import java.time.Instant;
import java.util.UUID;

public record MatchSummaryDto(
        UUID matchId,
        Instant matchedAt,
        Instant rewindEligibleUntil,
        MatchedUserSummaryDto otherUser
) {}
