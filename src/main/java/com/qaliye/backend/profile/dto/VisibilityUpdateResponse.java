package com.qaliye.backend.profile.dto;

public record VisibilityUpdateResponse(
        Boolean isVisible,
        Integer profileCompletionScore
) {}
