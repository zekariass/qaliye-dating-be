package com.qaliye.backend.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/payments/webhooks")
public class PaymentWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookController.class);

    private final StripeSignatureVerifier stripeVerifier;
    private final RevenueCatSignatureVerifier revenueCatVerifier;
    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    public PaymentWebhookController(StripeSignatureVerifier stripeVerifier,
                                    RevenueCatSignatureVerifier revenueCatVerifier,
                                    PaymentService paymentService,
                                    ObjectMapper objectMapper) {
        this.stripeVerifier = stripeVerifier;
        this.revenueCatVerifier = revenueCatVerifier;
        this.paymentService = paymentService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{provider}")
    public ResponseEntity<Map<String, Object>> handleWebhook(
            @PathVariable String provider,
            HttpServletRequest request,
            @RequestBody byte[] body) {

        PaymentSignatureVerifier verifier = switch (provider.toLowerCase()) {
            case "stripe" -> stripeVerifier;
            case "revenuecat" -> revenueCatVerifier;
            default -> null;
        };

        if (verifier == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "unknown_provider"));
        }

        if (!verifier.verify(request, body)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "invalid_signature"));
        }

        try {
            String providerUpper = switch (provider.toLowerCase()) {
                case "stripe" -> "STRIPE";
                case "revenuecat" -> "REVENUECAT";
                default -> provider.toUpperCase();
            };

            // Extract event ID and type for idempotency before full parse
            String eventId = extractEventId(provider, body);
            String eventType = extractEventType(provider, body);

            boolean isNew = paymentService.logAndCheck(providerUpper, eventId, eventType, body);
            if (!isNew) {
                return ResponseEntity.ok().build();
            }

            switch (provider.toLowerCase()) {
                case "stripe" -> paymentService.handleStripeWebhook(body);
                case "revenuecat" -> paymentService.handleRevenueCatWebhook(body);
                default -> log.warn("No handler for provider: {}", provider);
            }
        } catch (Exception e) {
            log.error("Webhook processing error for {}: {}", provider, e.getMessage());
        }

        return ResponseEntity.ok().build();
    }

    @SuppressWarnings("unchecked")
    private String extractEventId(String provider, byte[] body) {
        try {
            Map<String, Object> payload = objectMapper.readValue(body, Map.class);
            return switch (provider.toLowerCase()) {
                case "stripe" -> (String) payload.get("id");
                case "revenuecat" -> {
                    Map<String, Object> event = (Map<String, Object>) payload.get("event");
                    yield event != null ? (String) event.get("transaction_id") : null;
                }
                default -> null;
            };
        } catch (Exception e) {
            return java.util.UUID.randomUUID().toString();
        }
    }

    @SuppressWarnings("unchecked")
    private String extractEventType(String provider, byte[] body) {
        try {
            Map<String, Object> payload = objectMapper.readValue(body, Map.class);
            return switch (provider.toLowerCase()) {
                case "stripe" -> (String) payload.get("type");
                case "revenuecat" -> {
                    Map<String, Object> event = (Map<String, Object>) payload.get("event");
                    yield event != null ? (String) event.get("type") : "UNKNOWN";
                }
                default -> "UNKNOWN";
            };
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }
}
