package com.qaliye.backend.activity.dto;

import com.qaliye.backend.activity.ActivityStatus;

public record ActivityVisibilityResponse(
        boolean showActivityStatus,
        ActivityStatus activityStatus
) {}
