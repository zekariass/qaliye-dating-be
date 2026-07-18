package com.qaliye.backend.moderation;

import com.qaliye.backend.common.CallerUtils;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class ModerationController {

    @Value("${app.internal.webhook-secret}")
    private String webhookSecret;

    private final PhotoModerationService photoModerationService;
    private final NamedParameterJdbcTemplate jdbc;

    public ModerationController(PhotoModerationService photoModerationService,
                                NamedParameterJdbcTemplate jdbc) {
        this.photoModerationService = photoModerationService;
        this.jdbc = jdbc;
    }

    @PostMapping("/api/v1/internal/moderation/photo")
    public ResponseEntity<Void> handlePhotoWebhook(
            @RequestHeader(value = "X-Webhook-Secret", required = false) String secret,
            @RequestBody WebhookPayload payload) {

        if (!webhookSecret.equals(secret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        WebhookPayload.PhotoRecord rec = payload.getRecord();
        if (rec != null) {
            boolean isPrimary = resolveIsPrimary(rec.getId());
            photoModerationService.processPhotoModeration(
                    rec.getId(),
                    rec.getUserId(),
                    rec.getStoragePath(),
                    rec.getModerationStatus(),
                    isPrimary
            );
        }

        return ResponseEntity.accepted().build();
    }

    private boolean resolveIsPrimary(UUID photoId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT is_primary FROM profile_photos WHERE id = :id AND deleted_at IS NULL",
                Map.of("id", photoId));
        return !rows.isEmpty() && Boolean.TRUE.equals(rows.get(0).get("is_primary"));
    }

    @GetMapping("/api/v1/admin/moderation/photos")
    public ResponseEntity<Map<String, Object>> getPhotoQueue(
            @RequestParam(defaultValue = "PENDING") String status) {
        UUID callerId = CallerUtils.callerId();
        List<PhotoModerationItemDto> items = photoModerationService.getPhotoQueue(callerId, status);
        return ResponseEntity.ok(Map.of("items", items));
    }

    @PatchMapping("/api/v1/admin/moderation/photos/{photoId}")
    public ResponseEntity<Void> reviewPhoto(
            @PathVariable UUID photoId,
            @Valid @RequestBody PhotoReviewRequest request) {
        UUID callerId = CallerUtils.callerId();
        photoModerationService.reviewPhoto(callerId, photoId, request.getStatus());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/api/v1/admin/moderation/reports")
    public ResponseEntity<Map<String, Object>> getReportQueue(
            @RequestParam(defaultValue = "PENDING") String status) {
        UUID callerId = CallerUtils.callerId();
        List<ReportItemDto> items = photoModerationService.getReportQueue(callerId, status);
        return ResponseEntity.ok(Map.of("items", items));
    }

    @PatchMapping("/api/v1/admin/moderation/reports/{reportId}")
    public ResponseEntity<Void> resolveReport(
            @PathVariable UUID reportId,
            @Valid @RequestBody ReportResolutionRequest request) {
        UUID callerId = CallerUtils.callerId();
        photoModerationService.resolveReport(
                callerId, reportId, request.getResolution(), request.getBanReason());
        return ResponseEntity.ok().build();
    }
}
