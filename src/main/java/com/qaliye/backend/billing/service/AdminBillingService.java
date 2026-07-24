package com.qaliye.backend.billing.service;

import com.qaliye.backend.billing.repository.BillingRepository;
import com.qaliye.backend.storage.SupabaseStorageService;
import com.qaliye.backend.billing.BillingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class AdminBillingService {

    private static final Logger log = LoggerFactory.getLogger(AdminBillingService.class);

    private final BillingRepository billingRepo;
    private final FulfillmentService fulfillmentService;
    private final SupabaseStorageService storageService;
    private final BillingProperties billingProps;
    private final NamedParameterJdbcTemplate jdbc;

    public AdminBillingService(BillingRepository billingRepo,
                                FulfillmentService fulfillmentService,
                                SupabaseStorageService storageService,
                                BillingProperties billingProps,
                                NamedParameterJdbcTemplate jdbc) {
        this.billingRepo = billingRepo;
        this.fulfillmentService = fulfillmentService;
        this.storageService = storageService;
        this.billingProps = billingProps;
        this.jdbc = jdbc;
    }

    public Map<String, Object> listOrders(String status, String methodCode,
                                           String countryCode, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        List<String> statuses = parseStatuses(status);
        List<Map<String, Object>> orders = billingRepo.listOrders(statuses, methodCode, countryCode, pageSize, offset);
        long total = billingRepo.countOrders(statuses, methodCode);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orders", orders);
        result.put("total", total);
        result.put("page", page);
        result.put("pageSize", pageSize);
        return result;
    }

    public List<Map<String, Object>> listSubscriptionProducts() {
        return billingRepo.listSubscriptionProducts();
    }

    private List<String> parseStatuses(String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status.trim())) {
            return List.of();
        }
        return java.util.Arrays.stream(status.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    public Map<String, Object> getOrderDetails(UUID orderId) {
        BillingRepository.OrderRow order = billingRepo.findOrderById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "order_not_found"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", order.id());
        result.put("userId", order.userId());
        result.put("orderReference", order.orderReference());
        result.put("status", order.status());
        result.put("expectedAmountMinorUnits", order.expectedAmountMinorUnits());
        result.put("expectedCurrency", order.expectedCurrency());
        result.put("paymentMethodId", order.paymentMethodId());
        result.put("paymentChannel", order.paymentChannel());
        result.put("paymentMethod", order.paymentMethod());
        result.put("methodCode", order.methodCode());
        result.put("paymentMethodDisplayName", order.paymentMethodDisplayName());
        result.put("createdAt", order.createdAt());

        // Get receipt signed URL if applicable
        Optional<BillingRepository.ReceiptInfo> receipt = billingRepo.findReceiptProof(orderId);
        if (receipt.isPresent()) {
            BillingRepository.ReceiptInfo r = receipt.get();
            String signedUrl = storageService.generateSignedUrl(
                    r.bucket(), r.path(), billingProps.getReceiptSignedUrlTtlSeconds());
            result.put("receiptUrl", signedUrl);
        }

        return result;
    }

    @Transactional
    public Map<String, Object> approveOrder(UUID adminId, UUID orderId, String decisionNote) {
        enforceAdminRole(adminId);

        BillingRepository.OrderRow order = billingRepo.findOrderById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "order_not_found"));

        Set<String> approvableStatuses = Set.of(
                "MANUAL_REVIEW", "RECEIPT_SUBMITTED",
                "VERIFICATION_PENDING");
        if (!approvableStatuses.contains(order.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "order_not_reviewable");
        }

        // Create admin verification attempt
        UUID verificationId = billingRepo.insertVerificationAttempt(
                orderId, null, "ADMIN_REVIEW", "VERIFIED", "{}");
        billingRepo.updateVerificationAttempt(verificationId, "VERIFIED",
                order.expectedAmountMinorUnits(), order.expectedCurrency(),
                null, null, null, "{}", adminId, decisionNote);

        billingRepo.updateOrderStatus(orderId, "VERIFIED",
                "admin approved by " + adminId);

        // Fulfill
        fulfillmentService.fulfillVerifiedOrder(orderId, order.userId());

        // Audit
        logAudit(adminId, "APPROVE_ORDER", "payment_orders", orderId,
                Map.of("note", decisionNote != null ? decisionNote : "",
                       "previousStatus", order.status()));

        log.info("Admin {} approved order {} (was {})", adminId, orderId, order.status());
        return Map.of("status", "VERIFIED", "orderId", orderId);
    }

    @Transactional
    public Map<String, Object> rejectOrder(UUID adminId, UUID orderId, String decisionNote) {
        enforceAdminRole(adminId);

        BillingRepository.OrderRow order = billingRepo.findOrderById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "order_not_found"));

        Set<String> rejectableStatuses = Set.of(
                "MANUAL_REVIEW", "RECEIPT_SUBMITTED",
                "VERIFICATION_PENDING");
        if (!rejectableStatuses.contains(order.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "order_not_reviewable");
        }

        // Create admin verification attempt
        UUID verificationId = billingRepo.insertVerificationAttempt(
                orderId, null, "ADMIN_REVIEW", "REJECTED", "{}");
        billingRepo.updateVerificationAttempt(verificationId, "REJECTED",
                null, null, null, null, null, "{}", adminId, decisionNote);

        billingRepo.updateOrderStatus(orderId, "REJECTED",
                "admin rejected by " + adminId + (decisionNote != null ? ": " + decisionNote : ""));

        logAudit(adminId, "REJECT_ORDER", "payment_orders", orderId,
                Map.of("note", decisionNote != null ? decisionNote : "",
                       "previousStatus", order.status()));

        log.info("Admin {} rejected order {} (was {})", adminId, orderId, order.status());
        return Map.of("status", "REJECTED", "orderId", orderId);
    }

    private void enforceAdminRole(UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT role FROM app_users WHERE id = :userId",
                Map.of("userId", userId));
        if (rows.isEmpty() || !"ADMIN".equals(rows.get(0).get("role"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin_required");
        }
    }

    private void logAudit(UUID actorId, String action, String targetTable, UUID targetId,
                          Map<String, Object> details) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String detailsJson = mapper.writeValueAsString(details);
            jdbc.update("""
                    INSERT INTO audit_log (actor_user_id, action, target_table, target_id, details)
                    VALUES (:actorId, :action, :targetTable, :targetId, :details::jsonb)
                    """, Map.of("actorId", actorId, "action", action,
                    "targetTable", targetTable, "targetId", targetId,
                    "details", detailsJson));
        } catch (Exception e) {
            log.warn("Failed to log audit: {}", e.getMessage());
        }
    }
}
