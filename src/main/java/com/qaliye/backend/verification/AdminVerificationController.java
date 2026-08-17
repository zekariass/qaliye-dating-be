package com.qaliye.backend.verification;

import com.qaliye.backend.common.CallerUtils;
import com.qaliye.backend.user.UserStatusService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/identity-reviews")
public class AdminVerificationController {

    private final IdentityVerificationService identityVerificationService;
    private final UserStatusService userStatusService;

    public AdminVerificationController(IdentityVerificationService identityVerificationService,
                                        UserStatusService userStatusService) {
        this.identityVerificationService = identityVerificationService;
        this.userStatusService = userStatusService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listPendingReviews(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        UUID callerId = CallerUtils.callerId();
        requireAdmin(callerId);

        if (page < 1) page = 1;
        if (pageSize < 1 || pageSize > 100) pageSize = 20;

        return ResponseEntity.ok(identityVerificationService.getPendingReviews(page, pageSize));
    }

    @PostMapping("/{reviewId}/approve")
    public ResponseEntity<Map<String, Object>> approveReview(
            @PathVariable UUID reviewId,
            @RequestBody(required = false) ReviewNoteRequest request) {
        UUID callerId = CallerUtils.callerId();
        requireAdmin(callerId);

        String note = request != null ? request.getNote() : null;
        return ResponseEntity.ok(identityVerificationService.approveReview(callerId, reviewId, note));
    }

    @PostMapping("/{reviewId}/reject")
    public ResponseEntity<Map<String, Object>> rejectReview(
            @PathVariable UUID reviewId,
            @RequestBody(required = false) ReviewNoteRequest request) {
        UUID callerId = CallerUtils.callerId();
        requireAdmin(callerId);

        String note = request != null ? request.getNote() : null;
        return ResponseEntity.ok(identityVerificationService.rejectReview(callerId, reviewId, note));
    }

    private void requireAdmin(UUID callerId) {
        UserStatusService.UserStatus status = userStatusService.getStatus(callerId);
        if (status == null || !"ADMIN".equals(status.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin_access_required");
        }
    }

    public static class ReviewNoteRequest {
        private String note;

        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
    }
}
