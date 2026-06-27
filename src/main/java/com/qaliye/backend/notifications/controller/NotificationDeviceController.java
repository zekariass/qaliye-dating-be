package com.qaliye.backend.notifications.controller;

import com.qaliye.backend.notifications.service.NotificationDeviceService;
import com.qaliye.backend.notifications.service.NotificationDeviceService.RegisterRequest;
import com.qaliye.backend.notifications.service.NotificationDeviceService.RegisterResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications/devices")
public class NotificationDeviceController {

    private final NotificationDeviceService deviceService;

    public NotificationDeviceController(NotificationDeviceService deviceService) {
        this.deviceService = deviceService;
    }

    public record RegisterDeviceRequest(
            @NotBlank String expoPushToken,
            @NotBlank String platform,
            @NotNull UUID installationId
    ) {}

    public record RegisterDeviceResponse(boolean registered, boolean isActive) {}

    @PostMapping
    public ResponseEntity<RegisterDeviceResponse> register(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody RegisterDeviceRequest body) {

        UUID userId = UUID.fromString(jwt.getSubject());
        RegisterResult result = deviceService.register(userId,
                new RegisterRequest(body.expoPushToken(), body.platform(), body.installationId()));

        return ResponseEntity.ok(new RegisterDeviceResponse(result.registered(), result.isActive()));
    }

    @DeleteMapping("/current")
    public ResponseEntity<Void> deactivateCurrent(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("installation_id") UUID installationId) {

        UUID userId = UUID.fromString(jwt.getSubject());
        deviceService.deactivateCurrent(userId, installationId);
        return ResponseEntity.noContent().build();
    }
}
