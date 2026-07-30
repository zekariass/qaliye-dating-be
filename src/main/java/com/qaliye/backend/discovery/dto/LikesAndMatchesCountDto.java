package com.qaliye.backend.discovery.dto;

public record LikesAndMatchesCountDto(
        long receivedLikesCount,
        long sentLikesCount,
        long matchesCount
) {}
