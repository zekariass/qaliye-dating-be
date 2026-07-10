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
        double amount = amountMinorUnits / 100.0;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", String.valueOf(amount));
        body.put("currency", currency);
        body.put("tx_ref", orderReference);
        body.put("callback_url", billingProps.getChapa().getCallbackUrl());
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
            Map<String, Object> data = (Map<String, Object>) response.get("data");
            String checkoutUrl = data != null ? (String) data.get("checkout_url") : null;

            return new CheckoutResult(checkoutUrl, orderReference);
        } catch (Exception e) {
            log.error("Chapa checkout failed: {}", e.getMessage());
            throw new RuntimeException("chapa_checkout_failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> verifyTransaction(String txRef) {
        try {
            String responseStr = restClient.get()
                    .uri(billingProps.getChapa().getBaseUrl() + "/transaction/verify/" + txRef)
                    .header("Authorization", "Bearer " + billingProps.getChapa().getSecretKey())
                    .retrieve()
                    .body(String.class);

            return objectMapper.readValue(responseStr, Map.class);
        } catch (Exception e) {
            log.error("Chapa verification failed for txRef={}: {}", txRef, e.getMessage());
            return Map.of("status", "error", "message", e.getMessage());
        }
    }
}
