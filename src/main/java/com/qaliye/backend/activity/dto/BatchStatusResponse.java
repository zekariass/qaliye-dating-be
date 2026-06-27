package com.qaliye.backend.activity.dto;

import com.qaliye.backend.activity.ActivityStatus;

import java.util.List;
import java.util.UUID;

public record BatchStatusResponse(List<Item> items) {

    public record Item(UUID userId, ActivityStatus activityStatus) {}
}
