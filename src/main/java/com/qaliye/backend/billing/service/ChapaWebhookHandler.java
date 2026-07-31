package com.qaliye.backend.billing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaliye.backend.billing.provider.ChapaClient;
import com.qaliye.backend.billing.repository.BillingRepository;
import com.qaliye.backend.billing.repository.PromotionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ChapaWebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(ChapaWebhookHandler.class);

    private final BillingRepository billingRepo;
    private final FulfillmentService fulfillmentService;
    private final PromotionRepository promotionRepo;
    private final ObjectMapper objectMapper;
    private final ChapaClient chapaClient;

    public ChapaWebhookHandler(BillingRepository billingRepo,
                                FulfillmentService fulfillmentService,
                                PromotionRepository promotionRepo,
                                ObjectMapper objectMapper,
                                ChapaClient chapaClient) {
        this.billingRepo = billingRepo;
        this.fulfillmentService = fulfillmentService;
        this.promotionRepo = promotionRepo;
        this.objectMapper = objectMapper;
        this.chapaClient = chapaClient;
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public void handle(byte[] body) {
        try {
            Map<String, Object> payload = objectMapper.readValue(body, Map.class);

            String txRef = (String) payload.get("tx_ref");
            String chapaRef = (String) payload.get("reference");
            String status = (String) payload.get("status");
            String event = (String) payload.get("event");
            Object amountObj = payload.get("amount");
            String currency = (String) payload.get("currency");

            if (txRef == null) {
                log.warn("Chapa webhook: missing tx_ref");
                return;
            }

            // Log event for idempotency
            String eventId = chapaRef != null ? chapaRef : txRef;
            Optional<UUID> eventDbId = billingRepo.logEvent("CHAPA", eventId,
                    status != null ? status : "UNKNOWN",
                    new String(body), "PROCESSING");
            if (eventDbId.isEmpty()) {
                log.info("Chapa duplicate event ignored: ref={}", eventId);
                return;
            }

            // Find the order by reference (tx_ref = order_reference)
            Optional<BillingRepository.OrderRow> orderOpt = findOrderByReference(txRef);
            if (orderOpt.isEmpty()) {
                log.warn("Chapa webhook: no order found for tx_ref={}", txRef);
                return;
            }

            BillingRepository.OrderRow order = orderOpt.get();

            // Skip if order is already in a terminal state
            if ("VERIFIED".equals(order.status()) || "REJECTED".equals(order.status())
                    || "EXPIRED".equals(order.status()) || "CANCELLED".equals(order.status())) {
                log.info("Chapa webhook: order {} already in terminal status={}, skipping", order.id(), order.status());
                return;
            }

            if ("success".equalsIgnoreCase(status) || "charge.success".equalsIgnoreCase(event)) {
                // Server-side verification: always verify the final state of the transaction
                ChapaClient.VerifyResult verifyResult = chapaClient.verifyTransaction(txRef);
                if (verifyResult.isSuccess()) {
                    // Verify amount matches the order
                    Integer verifiedAmount = verifyResult.amountMinorUnits();
                    if (verifiedAmount != null && verifiedAmount != order.expectedAmountMinorUnits()) {
                        log.warn("Chapa amount mismatch: order={}, expected={}, verified={}",
                                order.id(), order.expectedAmountMinorUnits(), verifiedAmount);
                        billingRepo.updateOrderStatus(order.id(), "MANUAL_REVIEW",
                                "amount mismatch: expected=" + order.expectedAmountMinorUnits()
                                + ", verified=" + verifiedAmount);
                        return;
                    }
                    billingRepo.updateOrderStatus(order.id(), "VERIFIED", "Chapa payment verified");
                    fulfillmentService.fulfillVerifiedOrder(order.id(), order.userId());
                    log.info("Chapa payment verified and fulfilled: order={}, tx_ref={}", order.id(), txRef);
                } else if (verifyResult.isFailed()) {
                    billingRepo.updateOrderStatus(order.id(), "REJECTED",
                            "Chapa verify status: " + verifyResult.status());
                    promotionRepo.cancelRedemptionByOrderId(order.id(), "payment_failed");
                    log.info("Chapa payment rejected after verify: order={}, status={}", order.id(), verifyResult.status());
                } else {
                    log.warn("Chapa verify returned non-terminal status: order={}, verifyStatus={}, error={}",
                            order.id(), verifyResult.status(), verifyResult.errorMessage());
                    // Keep order in AWAITING_PAYMENT for future webhook retries
                }
            } else {
                log.info("Chapa payment not successful: order={}, status={}, event={}", order.id(), status, event);
                if ("failed".equalsIgnoreCase(status) || "cancelled".equalsIgnoreCase(status)
                        || (event != null && event.contains("failed"))) {
                    billingRepo.updateOrderStatus(order.id(), "REJECTED",
                            "Chapa payment " + status);
                    promotionRepo.cancelRedemptionByOrderId(order.id(), "payment_" + status);
                }
            }
        } catch (Exception e) {
            log.error("Chapa webhook processing error: {}", e.getMessage(), e);
        }
    }

    private Optional<BillingRepository.OrderRow> findOrderByReference(String orderReference) {
        return billingRepo.findOrderByReference(orderReference);
    }
}
