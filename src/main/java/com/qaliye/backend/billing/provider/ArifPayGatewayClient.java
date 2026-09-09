package com.qaliye.backend.billing.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaliye.backend.billing.BillingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class ArifPayGatewayClient implements LocalOnlinePaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(ArifPayGatewayClient.class);

    private static final String PRODUCTION_SESSION_PATH = "/api/checkout/session";
    private static final String SANDBOX_SESSION_PATH    = "/api/sandbox/c2b/session";
    private static final String PRODUCTION_VERIFY_PATH  = "/api/verify/transaction";
    private static final String SANDBOX_VERIFY_PATH     = "/api/sandbox/verify/transaction";

    private static final DateTimeFormatter EXPIRE_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final BillingProperties billingProps;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public ArifPayGatewayClient(BillingProperties billingProps,
                                RestClient restClient,
                                ObjectMapper objectMapper) {
        this.billingProps = billingProps;
        this.restClient   = restClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getMethodCode() {
        return "arifpay";
    }

    @Override
    public boolean isConfigured() {
        String key = billingProps.getArifPay().getSecretKey();
        return key != null && !key.isBlank();
    }

    @Override
    @SuppressWarnings("unchecked")
    public CheckoutResult createCheckout(String orderReference, int amountMinorUnits,
                                         String currency, String customerId, String returnUrl,
                                         String customerPhone, UUID orderId) {
        BillingProperties.ArifPay cfg = billingProps.getArifPay();
        double amount = amountMinorUnits / 100.0;
        String expireDate = EXPIRE_DATE_FMT.format(Instant.now().plus(48, ChronoUnit.HOURS));

        String orderIdParam = orderId != null ? orderId.toString() : orderReference;
        String effectiveSuccessUrl = cfg.getReturnUrl()
                + "?orderId=" + orderIdParam + "&provider=arifpay";
        String effectiveCancelUrl = cfg.getCancelUrl()
                + "?orderId=" + orderIdParam + "&provider=arifpay&status=cancelled";
        String effectiveErrorUrl  = cfg.getErrorUrl()
                + "?orderId=" + orderIdParam + "&provider=arifpay&status=error";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nonce",      orderReference);
        body.put("phone",      customerPhone);
        body.put("email",      cfg.getEmail());
        body.put("cancelUrl",  effectiveCancelUrl);
        body.put("errorUrl",   effectiveErrorUrl);
        body.put("notifyUrl",  cfg.getWebhookUrl());
        body.put("successUrl", effectiveSuccessUrl);
        body.put("expireDate", expireDate);
        body.put("items", List.of(Map.of(
                "name",        "Qaliye Premium",
                "quantity",    1,
                "price",       amount,
                "description", "Subscription payment"
        )));
        body.put("lang", "EN");

        String sessionPath = isSandbox() ? SANDBOX_SESSION_PATH : PRODUCTION_SESSION_PATH;

        try {
            String responseStr = restClient.post()
                    .uri(cfg.getBaseUrl() + sessionPath)
                    .header("x-arifpay-key", cfg.getSecretKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            Map<String, Object> response = objectMapper.readValue(responseStr, Map.class);
            Map<String, Object> data = (Map<String, Object>) response.get("data");

            String sessionId = null;
            String paymentUrl = null;

            if (data != null) {
                sessionId  = firstNonBlank((String) data.get("sessionId"),
                                           (String) data.get("SessionId"),
                                           (String) data.get("uuid"));
                paymentUrl = firstNonBlank((String) data.get("paymentUrl"),
                                           (String) data.get("approvalUrl"));
            }

            if (sessionId == null) {
                sessionId = (String) response.get("sessionId");
            }

            if (sessionId == null || sessionId.isBlank()) {
                log.error("ArifPay session create: no sessionId in response for nonce={}", orderReference);
                throw new ArifPayApiException("arifpay_no_session_id");
            }

            if (paymentUrl == null || paymentUrl.isBlank()) {
                paymentUrl = cfg.getBaseUrl() + "/checkout/" + sessionId;
                log.warn("ArifPay: no paymentUrl in response, using fallback: {}", paymentUrl);
            }

            log.info("ArifPay session created: nonce={} sessionId={}", orderReference, sessionId);
            return new CheckoutResult(paymentUrl, sessionId);
        } catch (ArifPayApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("ArifPay checkout failed for nonce={}: {}", orderReference, e.getMessage());
            throw new ArifPayApiException("arifpay_checkout_failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    public VerifyResult verifySession(String sessionId) {
        BillingProperties.ArifPay cfg = billingProps.getArifPay();
        try {
            String verifyPath = isSandbox() ? SANDBOX_VERIFY_PATH : PRODUCTION_VERIFY_PATH;
            String responseStr = restClient.get()
                    .uri(cfg.getBaseUrl() + verifyPath + "?uuid=" + sessionId)
                    .header("x-arifpay-key", cfg.getSecretKey())
                    .retrieve()
                    .body(String.class);

            log.debug("ArifPay verify raw response for sessionId={}: {}", sessionId, responseStr);

            Map<String, Object> data;
            try {
                Map<String, Object> response = objectMapper.readValue(responseStr, Map.class);
                data = (Map<String, Object>) response.get("data");
            } catch (com.fasterxml.jackson.core.JsonProcessingException jpe) {
                // ArifPay verify may return a JSON array at the top level
                var jsonNode = objectMapper.readTree(responseStr);
                if (jsonNode.isArray() && !jsonNode.isEmpty()) {
                    data = objectMapper.treeToValue(jsonNode.get(0), Map.class);
                } else {
                    log.warn("ArifPay verify: unexpected response format for sessionId={}: {}", sessionId, responseStr);
                    return new VerifyResult("UNKNOWN", null, null, null, null);
                }
            }

            if (data == null) {
                log.warn("ArifPay verify: no data in response for sessionId={}", sessionId);
                return new VerifyResult("UNKNOWN", null, null, null, null);
            }

            String status        = firstNonBlank((String) data.get("status"), (String) data.get("State"));
            String nonce         = (String) data.get("nonce");
            Object amountObj     = data.get("amount");
            String amountStr     = amountObj != null ? String.valueOf(amountObj) : null;
            String currency      = (String) data.get("currency");
            String transactionId = firstNonBlank((String) data.get("transactionId"),
                                                 (String) data.get("transaction"));

            return new VerifyResult(status, nonce, amountStr, currency, transactionId);
        } catch (Exception e) {
            log.error("ArifPay verify failed for sessionId={}: {}", sessionId, e.getMessage());
            return new VerifyResult("ERROR", null, null, null, null);
        }
    }

    public boolean isSandbox() {
        return "SANDBOX".equalsIgnoreCase(billingProps.getArifPay().getEnvironment());
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    public record VerifyResult(
            String status,
            String nonce,
            String amount,
            String currency,
            String transactionId
    ) {
        public boolean isSuccess() {
            return "SUCCESS".equalsIgnoreCase(status) || "COMPLETED".equalsIgnoreCase(status);
        }

        public boolean isFailed() {
            return "FAILED".equalsIgnoreCase(status)
                    || "CANCELLED".equalsIgnoreCase(status)
                    || "EXPIRED".equalsIgnoreCase(status);
        }

        public Integer amountMinorUnits() {
            if (amount == null) return null;
            try {
                return (int) Math.round(Double.parseDouble(amount) * 100);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    public static class ArifPayApiException extends RuntimeException {
        public ArifPayApiException(String message) { super(message); }
        public ArifPayApiException(String message, Throwable cause) { super(message, cause); }
    }
}
