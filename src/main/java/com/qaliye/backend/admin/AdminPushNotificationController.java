package com.qaliye.backend.admin;

import com.qaliye.backend.common.CallerUtils;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/notifications")
public class AdminPushNotificationController {

    private final AdminPushNotificationService pushService;

    public AdminPushNotificationController(AdminPushNotificationService pushService) {
        this.pushService = pushService;
    }

    public record SendPushRequest(String title, String body) {}

    @PostMapping("/users/{userId}/push")
    public ResponseEntity<Map<String, Object>> sendPush(
            @PathVariable UUID userId,
            @Valid @RequestBody SendPushRequest request) {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(pushService.sendPushNotification(
                callerId, userId, request.title(), request.body()));
    }
}
