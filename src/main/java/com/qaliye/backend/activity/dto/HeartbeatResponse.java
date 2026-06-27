package com.qaliye.backend.activity.dto;

import com.qaliye.backend.activity.ActivityStatus;

public record HeartbeatResponse(
        ActivityStatus activityStatus,
        boolean showActivityStatus
) {}
