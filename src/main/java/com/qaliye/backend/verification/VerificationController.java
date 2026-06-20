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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class VerificationController {

    private final VerificationService verificationService;

    public VerificationController(VerificationService verificationService) {
        this.verificationService = verificationService;
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
