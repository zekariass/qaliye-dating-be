package com.qaliye.backend.activity;

import com.qaliye.backend.activity.dto.ActivityVisibilityRequest;
import com.qaliye.backend.activity.dto.ActivityVisibilityResponse;
import com.qaliye.backend.activity.dto.BatchStatusRequest;
import com.qaliye.backend.activity.dto.BatchStatusResponse;
import com.qaliye.backend.activity.dto.HeartbeatResponse;
import com.qaliye.backend.common.CallerUtils;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class ActivityController {

    private final ActivityStatusService activityStatusService;
    private final ActivityVisibilitySettingsService visibilityService;

    public ActivityController(ActivityStatusService activityStatusService,
                              ActivityVisibilitySettingsService visibilityService) {
        this.activityStatusService = activityStatusService;
        this.visibilityService = visibilityService;
    }

    @PostMapping("/api/v1/activity/heartbeat")
    public ResponseEntity<HeartbeatResponse> heartbeat() {
        UUID callerId = CallerUtils.callerId();
        ActivityStatusService.HeartbeatResult result = activityStatusService.heartbeat(callerId);
        return ResponseEntity.ok(new HeartbeatResponse(result.activityStatus(), result.showActivityStatus()));
    }

    @PostMapping("/api/v1/activity/statuses")
    public ResponseEntity<BatchStatusResponse> getBatchStatuses(
            @Valid @RequestBody BatchStatusRequest request) {
        UUID callerId = CallerUtils.callerId();
        List<ActivityStatusService.BatchStatusItem> items =
                activityStatusService.getBatchStatuses(callerId, request.userIds());
        List<BatchStatusResponse.Item> responseItems = items.stream()
                .map(i -> new BatchStatusResponse.Item(i.userId(), i.activityStatus()))
                .toList();
        return ResponseEntity.ok(new BatchStatusResponse(responseItems));
    }

    @PatchMapping("/api/v1/users/me/activity-visibility")
    public ResponseEntity<ActivityVisibilityResponse> updateVisibility(
            @Valid @RequestBody ActivityVisibilityRequest request) {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(visibilityService.updateVisibility(callerId, request.showActivityStatus()));
    }
}
