package com.qaliye.backend.verification;

import com.qaliye.backend.common.CallerUtils;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class VerificationController {

    private final VerificationService verificationService;
    private final IdentityVerificationService identityVerificationService;

    public VerificationController(VerificationService verificationService,
                                   IdentityVerificationService identityVerificationService) {
        this.verificationService = verificationService;
        this.identityVerificationService = identityVerificationService;
    }

    @PostMapping("/verification/submit")
    public ResponseEntity<Map<String, Object>> submit(
            @Valid @RequestBody SubmitVerificationRequest request) {
        UUID callerId = CallerUtils.callerId();
        UUID verificationId = verificationService.submitVerification(callerId, request.getStoragePath());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("verification_id", verificationId);
        body.put("status", "PENDING");
        return ResponseEntity.ok(body);
    }

    @GetMapping("/admin/verification/queue")
    public ResponseEntity<Map<String, Object>> getQueue(
            @RequestParam(defaultValue = "PENDING") String status) {
        UUID callerId = CallerUtils.callerId();
        List<VerificationQueueItemDto> items = verificationService.getQueue(callerId, status);
        return ResponseEntity.ok(Map.of("items", items));
    }

    @PatchMapping("/admin/verification/{verificationId}")
    public ResponseEntity<Map<String, Object>> review(
            @PathVariable UUID verificationId,
            @Valid @RequestBody ReviewVerificationRequest request) {
        UUID callerId = CallerUtils.callerId();
        Map<String, Object> result = verificationService.reviewVerification(callerId, verificationId, request);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/profile/identity-verification")
    public ResponseEntity<Map<String, Object>> identityVerification(
            @RequestParam("selfie") MultipartFile selfie) throws IOException {
        UUID callerId = CallerUtils.callerId();

        if (selfie == null || selfie.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "selfie_required", "message", "A selfie image is required."));
        }

        String contentType = selfie.getContentType();
        if (contentType == null || !contentType.matches("image/(jpeg|png|jpg)")) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "unsupported_image_format",
                            "message", "Selfie must be a JPEG or PNG image."));
        }

        byte[] selfieBytes = selfie.getBytes();
        IdentityVerificationService.IdentityVerificationResponse result =
                identityVerificationService.verify(callerId, selfieBytes);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("verification_status", result.verificationStatus());
        if (result.errorCode() != null) {
            body.put("error_code", result.errorCode());
        }
        body.put("message", result.resultMessage());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/profile/identity-verification/manual-review/status")
    public ResponseEntity<Map<String, Object>> getManualReviewStatus() {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(identityVerificationService.getManualReviewStatus(callerId));
    }

    @PostMapping("/profile/identity-verification/manual-review")
    public ResponseEntity<Map<String, Object>> requestManualReview(
            @RequestParam("selfie") MultipartFile selfie) throws IOException {
        UUID callerId = CallerUtils.callerId();

        if (selfie == null || selfie.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "selfie_required", "message", "A selfie image is required."));
        }

        String contentType = selfie.getContentType();
        if (contentType == null || !contentType.matches("image/(jpeg|png|jpg)")) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "unsupported_image_format",
                            "message", "Selfie must be a JPEG or PNG image."));
        }

        byte[] selfieBytes = selfie.getBytes();
        IdentityVerificationService.IdentityVerificationResponse result =
                identityVerificationService.requestManualReview(callerId, selfieBytes);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("verification_status", result.verificationStatus());
        if (result.errorCode() != null) {
            body.put("error_code", result.errorCode());
        }
        body.put("message", result.resultMessage());
        return ResponseEntity.ok(body);
    }

    @ExceptionHandler(IdentityVerificationException.class)
    public ResponseEntity<Map<String, Object>> handleIdentityVerificationException(IdentityVerificationException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", ex.getErrorCode());
        body.put("message", ex.getErrorMessage());
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    @ExceptionHandler(VerificationException.class)
    public ResponseEntity<Map<String, Object>> handleVerificationException(VerificationException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", ex.getError());
        if (ex.getErrorMessage() != null) {
            body.put("message", ex.getErrorMessage());
        }
        return ResponseEntity.status(ex.getStatus()).body(body);
    }
}
