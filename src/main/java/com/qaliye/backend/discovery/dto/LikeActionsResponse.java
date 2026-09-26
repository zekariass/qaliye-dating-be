package com.qaliye.backend.discovery.dto;

import java.util.List;

public record LikeActionsResponse(
        List<LikeActionDto> actions
) {}
