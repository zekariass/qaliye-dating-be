package com.qaliye.backend.notifications.controller;

import com.qaliye.backend.notifications.entity.NotificationCampaign;
import com.qaliye.backend.notifications.service.NotificationCampaignService;
import com.qaliye.backend.notifications.service.NotificationCampaignService.CreateRequest;
import com.qaliye.backend.notifications.service.NotificationCampaignService.UpdateRequest;
import com.qaliye.backend.user.UserStatusService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/notification-campaigns")
public class NotificationCampaignAdminController {

    private final NotificationCampaignService campaignService;
    private final UserStatusService userStatusService;

    public NotificationCampaignAdminController(NotificationCampaignService campaignService,
                                               UserStatusService userStatusService) {
        this.campaignService = campaignService;
        this.userStatusService = userStatusService;
    }

    public record CreateCampaignRequest(
            @NotBlank String campaignKey,
            @NotBlank String title,
            @NotBlank String body,
            Map<String, Object> navigationPayload,
            Map<String, Object> audienceDefinition
    ) {}

    public record UpdateCampaignRequest(
            String title,
            String body,
            Map<String, Object> navigationPayload,
            Map<String, Object> audienceDefinition,
            String status,
            OffsetDateTime scheduledAt
    ) {}

    @GetMapping
    public ResponseEntity<Page<NotificationCampaign>> listCampaigns(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        requireAdmin(jwt);
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        return ResponseEntity.ok(campaignService.listCampaigns(status, pageable));
    }

    @GetMapping("/{campaignId}")
    public ResponseEntity<NotificationCampaign> getCampaign(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID campaignId) {

        requireAdmin(jwt);
        return ResponseEntity.ok(campaignService.getCampaign(campaignId));
    }

    @PostMapping
    public ResponseEntity<NotificationCampaign> createCampaign(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateCampaignRequest body) {

        requireAdmin(jwt);
        UUID userId = UUID.fromString(jwt.getSubject());
        NotificationCampaign created = campaignService.createCampaign(userId,
                new CreateRequest(
                        body.campaignKey(),
                        body.title(),
                        body.body(),
                        body.navigationPayload(),
                        body.audienceDefinition()
                ));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/{campaignId}")
    public ResponseEntity<NotificationCampaign> updateCampaign(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID campaignId,
            @RequestBody UpdateCampaignRequest body) {

        requireAdmin(jwt);
        NotificationCampaign updated = campaignService.updateCampaign(campaignId,
                new UpdateRequest(
                        body.title(),
                        body.body(),
                        body.navigationPayload(),
                        body.audienceDefinition(),
                        body.status(),
                        body.scheduledAt()
                ));
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/{campaignId}/start")
    public ResponseEntity<NotificationCampaign> startCampaign(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID campaignId) {

        requireAdmin(jwt);
        return ResponseEntity.ok(campaignService.startCampaign(campaignId));
    }

    @PostMapping("/{campaignId}/cancel")
    public ResponseEntity<NotificationCampaign> cancelCampaign(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID campaignId) {

        requireAdmin(jwt);
        return ResponseEntity.ok(campaignService.cancelCampaign(campaignId));
    }

    private void requireAdmin(Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        UserStatusService.UserStatus status = userStatusService.getStatus(userId);
        if (status == null || !"ADMIN".equals(status.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Admin access required.");
        }
    }
}
