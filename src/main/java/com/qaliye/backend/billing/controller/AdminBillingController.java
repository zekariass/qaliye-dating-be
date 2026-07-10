package com.qaliye.backend.billing.controller;

import com.qaliye.backend.billing.dto.AdminApproveRequest;
import com.qaliye.backend.billing.dto.AdminDeclineRequest;
import com.qaliye.backend.billing.dto.AdminRejectRequest;
import com.qaliye.backend.billing.service.AdminBillingService;
import com.qaliye.backend.common.CallerUtils;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/billing")
public class AdminBillingController {

    private final AdminBillingService adminBillingService;

    public AdminBillingController(AdminBillingService adminBillingService) {
        this.adminBillingService = adminBillingService;
    }

    @GetMapping("/orders")
    public ResponseEntity<Map<String, Object>> listOrders(
            @RequestParam(defaultValue = "MANUAL_REVIEW,RECEIPT_SUBMITTED") String status,
            @RequestParam(required = false) String methodCode,
            @RequestParam(required = false) String countryCode,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok(adminBillingService.listOrders(status, methodCode, countryCode, page, pageSize));
    }

    @GetMapping("/orders/{orderId}")
    public ResponseEntity<Map<String, Object>> getOrderDetails(@PathVariable UUID orderId) {
        return ResponseEntity.ok(adminBillingService.getOrderDetails(orderId));
    }

    @PostMapping("/orders/{orderId}/approve")
    public ResponseEntity<Map<String, Object>> approveOrder(
            @PathVariable UUID orderId,
            @RequestBody(required = false) AdminApproveRequest request) {
        UUID adminId = CallerUtils.callerId();
        String note = request != null ? request.decisionNote() : null;
        return ResponseEntity.ok(adminBillingService.approveOrder(adminId, orderId, note));
    }

    @PostMapping("/orders/{orderId}/decline")
    public ResponseEntity<Map<String, Object>> declineOrder(
            @PathVariable UUID orderId,
            @Valid @RequestBody AdminDeclineRequest request) {
        UUID adminId = CallerUtils.callerId();
        return ResponseEntity.ok(adminBillingService.rejectOrder(adminId, orderId, request.decisionNote()));
    }

    @PostMapping("/orders/{orderId}/reject")
    public ResponseEntity<Map<String, Object>> rejectOrder(
            @PathVariable UUID orderId,
            @Valid @RequestBody(required = false) AdminRejectRequest request) {
        UUID adminId = CallerUtils.callerId();
        String note = request != null ? request.decisionNote() : null;
        return ResponseEntity.ok(adminBillingService.rejectOrder(adminId, orderId, note));
    }
}
