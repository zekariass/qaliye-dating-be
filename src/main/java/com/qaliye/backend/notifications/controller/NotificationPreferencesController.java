package com.qaliye.backend.notifications.controller;

import com.qaliye.backend.notifications.entity.UserNotificationPreferences;
import com.qaliye.backend.notifications.service.NotificationPreferencesService;
import com.qaliye.backend.notifications.service.NotificationPreferencesService.UpdateRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications/preferences")
public class NotificationPreferencesController {

    private final NotificationPreferencesService preferencesService;

    public NotificationPreferencesController(NotificationPreferencesService preferencesService) {
        this.preferencesService = preferencesService;
    }

    public record PreferencesResponse(
            boolean pushEnabled,
            boolean messageNotificationsEnabled,
            boolean matchNotificationsEnabled,
            boolean likeNotificationsEnabled,
            boolean messagePreviewEnabled,
            boolean marketingNotificationsEnabled,
            OffsetDateTime marketingNotificationsOptedInAt,
            String marketingNotificationsConsentVersion
    ) {}

    public record UpdatePreferencesRequest(
            Boolean pushEnabled,
            Boolean messageNotificationsEnabled,
            Boolean matchNotificationsEnabled,
            Boolean likeNotificationsEnabled,
            Boolean messagePreviewEnabled,
            Boolean marketingNotificationsEnabled,
            String marketingNotificationsConsentVersion
    ) {}

    @GetMapping
    public ResponseEntity<PreferencesResponse> getPreferences(
            @AuthenticationPrincipal Jwt jwt) {

        UUID userId = UUID.fromString(jwt.getSubject());
        UserNotificationPreferences prefs = preferencesService.getPreferences(userId);
        return ResponseEntity.ok(toResponse(prefs));
    }

    @PatchMapping
    public ResponseEntity<PreferencesResponse> updatePreferences(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody UpdatePreferencesRequest body) {

        UUID userId = UUID.fromString(jwt.getSubject());
        UserNotificationPreferences updated = preferencesService.updatePreferences(userId,
                new UpdateRequest(
                        body.pushEnabled(),
                        body.messageNotificationsEnabled(),
                        body.matchNotificationsEnabled(),
                        body.likeNotificationsEnabled(),
                        body.messagePreviewEnabled(),
                        body.marketingNotificationsEnabled(),
                        body.marketingNotificationsConsentVersion()
                ));
        return ResponseEntity.ok(toResponse(updated));
    }

    private PreferencesResponse toResponse(UserNotificationPreferences p) {
        return new PreferencesResponse(
                p.isPushEnabled(),
                p.isMessageNotificationsEnabled(),
                p.isMatchNotificationsEnabled(),
                p.isLikeNotificationsEnabled(),
                p.isMessagePreviewEnabled(),
                p.isMarketingNotificationsEnabled(),
                p.getMarketingNotificationsOptedInAt(),
                p.getMarketingNotificationsConsentVersion()
        );
    }
}
