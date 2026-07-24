package com.qaliye.backend.admin;

import com.qaliye.backend.common.CallerUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/billing")
public class AdminRefundController {

    private final AdminRefundService refundService;

    public AdminRefundController(AdminRefundService refundService) {
        this.refundService = refundService;
    }

    public record RefundRequest(String reason) {}

    @PostMapping("/orders/{orderId}/refund")
    public ResponseEntity<Map<String, Object>> refundOrder(
            @PathVariable UUID orderId,
            @RequestBody(required = false) RefundRequest request) {
        UUID callerId = CallerUtils.callerId();
        String reason = request != null ? request.reason() : null;
        return ResponseEntity.ok(refundService.refundOrder(callerId, orderId, reason));
    }
}
