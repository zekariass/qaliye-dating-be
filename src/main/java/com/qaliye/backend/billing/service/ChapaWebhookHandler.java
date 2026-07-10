package com.qaliye.backend.billing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaliye.backend.billing.repository.BillingRepository;
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
    private final ObjectMapper objectMapper;

    public ChapaWebhookHandler(BillingRepository billingRepo,
                                FulfillmentService fulfillmentService,
                                ObjectMapper objectMapper) {
        this.billingRepo = billingRepo;
        this.fulfillmentService = fulfillmentService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public void handle(byte[] body) {
        try {
            Map<String, Object> payload = objectMapper.readValue(body, Map.class);

            String txRef = (String) payload.get("tx_ref");
            String chapaRef = (String) payload.get("reference");
            String status = (String) payload.get("status");
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
            // We search for the order that matches this tx_ref
            Optional<BillingRepository.OrderRow> orderOpt = findOrderByReference(txRef);
            if (orderOpt.isEmpty()) {
                log.warn("Chapa webhook: no order found for tx_ref={}", txRef);
                return;
            }

            BillingRepository.OrderRow order = orderOpt.get();

            if ("success".equalsIgnoreCase(status)) {
                billingRepo.updateOrderStatus(order.id(), "VERIFIED", "Chapa payment success");
                fulfillmentService.fulfillVerifiedOrder(order.id(), order.userId());
                log.info("Chapa payment verified and fulfilled: order={}, tx_ref={}", order.id(), txRef);
            } else {
                log.info("Chapa payment not successful: order={}, status={}", order.id(), status);
                if ("failed".equalsIgnoreCase(status) || "cancelled".equalsIgnoreCase(status)) {
                    billingRepo.updateOrderStatus(order.id(), "REJECTED",
                            "Chapa payment " + status);
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
