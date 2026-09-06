package com.qaliye.backend.billing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaliye.backend.billing.provider.ArifPayGatewayClient;
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
public class ArifPayWebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(ArifPayWebhookHandler.class);

    private final BillingRepository billingRepo;
    private final FulfillmentService fulfillmentService;
    private final PromotionRepository promotionRepo;
    private final ObjectMapper objectMapper;
    private final ArifPayGatewayClient arifPayClient;

    public ArifPayWebhookHandler(BillingRepository billingRepo,
                                  FulfillmentService fulfillmentService,
                                  PromotionRepository promotionRepo,
                                  ObjectMapper objectMapper,
                                  ArifPayGatewayClient arifPayClient) {
        this.billingRepo      = billingRepo;
        this.fulfillmentService = fulfillmentService;
        this.promotionRepo    = promotionRepo;
        this.objectMapper     = objectMapper;
        this.arifPayClient    = arifPayClient;
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public void handle(byte[] body) {
        try {
            Map<String, Object> payload = objectMapper.readValue(body, Map.class);

            String nonce     = (String) payload.get("nonce");
            String sessionId = firstNonBlank((String) payload.get("sessionId"),
                                             (String) payload.get("uuid"));
            String status    = firstNonBlank((String) payload.get("transactionStatus"),
                                             (String) payload.get("status"));

            // transactionId may be at the top level or nested inside "transaction" object
            String transactionId = (String) payload.get("transactionId");
            if (transactionId == null && payload.get("transaction") instanceof Map<?, ?> txn) {
                transactionId = (String) txn.get("transactionId");
            }

            if (nonce == null && sessionId == null) {
                log.warn("ArifPay webhook: missing both nonce and sessionId");
                return;
            }

            // Log event for idempotency (prefer transactionId → sessionId → nonce)
            String eventId = firstNonBlank(transactionId, sessionId, nonce);
            Optional<UUID> eventDbId = billingRepo.logEvent("ARIFPAY", eventId,
                    status != null ? status : "UNKNOWN",
                    new String(body), "PROCESSING");
            if (eventDbId.isEmpty()) {
                log.info("ArifPay duplicate event ignored: eventId={}", eventId);
                return;
            }

            if (nonce == null) {
                log.warn("ArifPay webhook: no nonce for sessionId={}, cannot find order", sessionId);
                return;
            }

            // Find order by nonce (= orderReference)
            Optional<BillingRepository.OrderRow> orderOpt = billingRepo.findOrderByReference(nonce);
            if (orderOpt.isEmpty()) {
                log.warn("ArifPay webhook: no order found for nonce={}", nonce);
                return;
            }

            BillingRepository.OrderRow order = orderOpt.get();

            // Skip terminal orders
            if ("VERIFIED".equals(order.status()) || "REJECTED".equals(order.status())
                    || "EXPIRED".equals(order.status()) || "CANCELLED".equals(order.status())) {
                log.info("ArifPay webhook: order {} already terminal status={}, skipping",
                        order.id(), order.status());
                return;
            }

            // Resolve sessionId for verification: prefer webhook payload, fall back to stored reference
            String verifySessionId = firstNonBlank(sessionId, order.providerOrderReference());
            if (verifySessionId == null) {
                log.warn("ArifPay webhook: no sessionId available for verification, order={}", order.id());
                return;
            }

            if ("SUCCESS".equalsIgnoreCase(status) || "COMPLETED".equalsIgnoreCase(status)) {
                // Always verify server-side regardless of webhook claim
                ArifPayGatewayClient.VerifyResult verifyResult = arifPayClient.verifySession(verifySessionId);

                // Sandbox does not expose a verify endpoint; trust the webhook status directly
                if ("ERROR".equals(verifyResult.status()) && arifPayClient.isSandbox()) {
                    log.info("ArifPay sandbox: verify endpoint unavailable, trusting webhook status={} for order={}",
                            status, order.id());
                    verifyResult = new ArifPayGatewayClient.VerifyResult(status, nonce, null, null, transactionId);
                }

                if (verifyResult.isSuccess()) {
                    Integer verifiedAmount = verifyResult.amountMinorUnits();
                    if (verifiedAmount != null && verifiedAmount != order.expectedAmountMinorUnits()) {
                        log.warn("ArifPay amount mismatch: order={}, expected={}, verified={}",
                                order.id(), order.expectedAmountMinorUnits(), verifiedAmount);
                        billingRepo.updateOrderStatus(order.id(), "MANUAL_REVIEW",
                                "amount mismatch: expected=" + order.expectedAmountMinorUnits()
                                + ", verified=" + verifiedAmount);
                    } else {
                        billingRepo.updateOrderStatus(order.id(), "VERIFIED", "ArifPay payment verified");
                        fulfillmentService.fulfillVerifiedOrder(order.id(), order.userId());
                        log.info("ArifPay payment verified and fulfilled: order={} nonce={} sessionId={}",
                                order.id(), nonce, verifySessionId);
                    }
                } else if (verifyResult.isFailed()) {
                    billingRepo.updateOrderStatus(order.id(), "REJECTED",
                            "ArifPay verify status: " + verifyResult.status());
                    promotionRepo.cancelRedemptionByOrderId(order.id(), "payment_failed");
                    log.info("ArifPay payment rejected after verify: order={} verifyStatus={}",
                            order.id(), verifyResult.status());
                } else {
                    log.warn("ArifPay verify non-terminal: order={} verifyStatus={}",
                            order.id(), verifyResult.status());
                }
            } else {
                log.info("ArifPay payment not successful: order={} status={}", order.id(), status);
                if ("FAILED".equalsIgnoreCase(status) || "CANCELLED".equalsIgnoreCase(status)
                        || "EXPIRED".equalsIgnoreCase(status)) {
                    String lowerStatus = status != null ? status.toLowerCase() : "failed";
                    billingRepo.updateOrderStatus(order.id(), "REJECTED", "ArifPay payment " + status);
                    promotionRepo.cancelRedemptionByOrderId(order.id(), "payment_" + lowerStatus);
                }
            }
        } catch (Exception e) {
            log.error("ArifPay webhook processing error: {}", e.getMessage(), e);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
