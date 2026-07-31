package com.qaliye.backend.billing.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaliye.backend.billing.BillingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ChapaClient implements LocalOnlinePaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(ChapaClient.class);

    private final BillingProperties billingProps;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public ChapaClient(BillingProperties billingProps, RestClient restClient, ObjectMapper objectMapper) {
        this.billingProps = billingProps;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getMethodCode() {
        return "chapa";
    }

    @Override
    public boolean isConfigured() {
        String key = billingProps.getChapa().getSecretKey();
        return key != null && !key.isBlank();
    }

    @Override
    @SuppressWarnings("unchecked")
    public CheckoutResult createCheckout(String orderReference, int amountMinorUnits,
                                         String currency, String customerId) {
        String amount = String.format("%.2f", amountMinorUnits / 100.0);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amount);
        body.put("currency", currency);
        body.put("tx_ref", orderReference);
        body.put("callback_url", billingProps.getChapa().getWebhookUrl());

        String returnUrl = billingProps.getChapa().getReturnUrl();
        if (returnUrl != null && !returnUrl.isBlank()
                && (returnUrl.startsWith("http://") || returnUrl.startsWith("https://"))) {
            body.put("return_url", returnUrl);
        }

        body.put("customization", Map.of("title", "Qaliye Premium", "description", "Subscription payment"));

        try {
            String responseStr = restClient.post()
                    .uri(billingProps.getChapa().getBaseUrl() + "/transaction/initialize")
                    .header("Authorization", "Bearer " + billingProps.getChapa().getSecretKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            Map<String, Object> response = objectMapper.readValue(responseStr, Map.class);
            String status = (String) response.get("status");
            if (!"success".equalsIgnoreCase(status)) {
                String message = (String) response.get("message");
                log.error("Chapa initialize failed: status={}, message={}", status, message);
                throw new ChapaApiException("chapa_initialize_failed: " + message);
            }

            Map<String, Object> data = (Map<String, Object>) response.get("data");
            String checkoutUrl = data != null ? (String) data.get("checkout_url") : null;
            String txRef = data != null ? (String) data.get("tx_ref") : orderReference;

            if (checkoutUrl == null || checkoutUrl.isBlank()) {
                log.error("Chapa initialize: no checkout_url in response");
                throw new ChapaApiException("chapa_initialize_no_checkout_url");
            }

            return new CheckoutResult(checkoutUrl, txRef);
        } catch (ChapaApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Chapa checkout failed: {}", e.getMessage());
            throw new ChapaApiException("chapa_checkout_failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    public VerifyResult verifyTransaction(String txRef) {
        try {
            String responseStr = restClient.get()
                    .uri(billingProps.getChapa().getBaseUrl() + "/transaction/verify/" + txRef)
                    .header("Authorization", "Bearer " + billingProps.getChapa().getSecretKey())
                    .retrieve()
                    .body(String.class);

            Map<String, Object> response = objectMapper.readValue(responseStr, Map.class);
            String responseStatus = (String) response.get("status");

            if (!"success".equalsIgnoreCase(responseStatus)) {
                String message = (String) response.get("message");
                log.warn("Chapa verify failed: txRef={}, status={}, message={}", txRef, responseStatus, message);
                return new VerifyResult("error", null, null, null, null, message);
            }

            Map<String, Object> data = (Map<String, Object>) response.get("data");
            if (data == null) {
                log.warn("Chapa verify: no data in response for txRef={}", txRef);
                return new VerifyResult("error", null, null, null, null, "no data in response");
            }

            String status = (String) data.get("status");
            String reference = (String) data.get("reference");
            String amountStr = data.get("amount") != null ? String.valueOf(data.get("amount")) : null;
            String currency = (String) data.get("currency");
            String paymentMethod = (String) data.get("payment_method");

            return new VerifyResult(status, reference, amountStr, currency, paymentMethod, null);
        } catch (Exception e) {
            log.error("Chapa verification failed for txRef={}: {}", txRef, e.getMessage());
            return new VerifyResult("error", null, null, null, null, e.getMessage());
        }
    }

    public record VerifyResult(
            String status,
            String reference,
            String amount,
            String currency,
            String paymentMethod,
            String errorMessage
    ) {
        public boolean isSuccess() {
            return "success".equalsIgnoreCase(status);
        }

        public boolean isFailed() {
            return "failed".equalsIgnoreCase(status) || "cancelled".equalsIgnoreCase(status);
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

    public static class ChapaApiException extends RuntimeException {
        public ChapaApiException(String message) {
            super(message);
        }

        public ChapaApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
