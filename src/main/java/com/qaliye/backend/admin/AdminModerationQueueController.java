package com.qaliye.backend.admin;

import com.qaliye.backend.common.CallerUtils;
import com.qaliye.backend.moderation.PhotoModerationItemDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/moderation")
public class AdminModerationQueueController {

    private final AdminModerationQueueService queueService;

    public AdminModerationQueueController(AdminModerationQueueService queueService) {
        this.queueService = queueService;
    }

    @GetMapping("/photos/manual-review")
    public ResponseEntity<Map<String, Object>> getManualReviewQueue() {
        var callerId = CallerUtils.callerId();
        List<PhotoModerationItemDto> items = queueService.getManualReviewQueue(callerId);
        return ResponseEntity.ok(Map.of("items", items));
    }

    @GetMapping("/photos/review-queue")
    public ResponseEntity<Map<String, Object>> getReviewQueue() {
        var callerId = CallerUtils.callerId();
        List<PhotoModerationItemDto> items = queueService.getReviewQueue(callerId);
        return ResponseEntity.ok(Map.of("items", items));
    }

    @GetMapping("/photos/counts")
    public ResponseEntity<Map<String, Object>> getQueueCounts() {
        var callerId = CallerUtils.callerId();
        return ResponseEntity.ok(queueService.getQueueCounts(callerId));
    }

    @PatchMapping("/photos/{photoId}/approve")
    public ResponseEntity<Map<String, Object>> approvePhoto(@PathVariable UUID photoId) {
        var callerId = CallerUtils.callerId();
        return ResponseEntity.ok(queueService.approvePhoto(callerId, photoId));
    }

    @PatchMapping("/photos/{photoId}/reject")
    public ResponseEntity<Map<String, Object>> rejectPhoto(
            @PathVariable UUID photoId,
            @RequestBody(required = false) Map<String, String> body) {
        var callerId = CallerUtils.callerId();
        String reason = body != null ? body.get("reason") : null;
        return ResponseEntity.ok(queueService.rejectPhoto(callerId, photoId, reason));
    }
}
