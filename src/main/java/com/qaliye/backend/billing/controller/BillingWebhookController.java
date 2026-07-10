package com.qaliye.backend.billing.controller;

import com.qaliye.backend.billing.BillingProperties;
import com.qaliye.backend.billing.service.ChapaWebhookHandler;
import com.qaliye.backend.billing.service.RevenueCatWebhookHandler;
import com.qaliye.backend.billing.service.VerifyEtWebhookHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/billing/webhooks")
public class BillingWebhookController {

    private static final Logger log = LoggerFactory.getLogger(BillingWebhookController.class);

    private final RevenueCatWebhookHandler revenueCatHandler;
    private final ChapaWebhookHandler chapaHandler;
    private final VerifyEtWebhookHandler verifyEtHandler;
    private final BillingProperties billingProps;

    public BillingWebhookController(RevenueCatWebhookHandler revenueCatHandler,
                                     ChapaWebhookHandler chapaHandler,
                                     VerifyEtWebhookHandler verifyEtHandler,
                                     BillingProperties billingProps) {
        this.revenueCatHandler = revenueCatHandler;
        this.chapaHandler = chapaHandler;
        this.verifyEtHandler = verifyEtHandler;
        this.billingProps = billingProps;
    }

    @PostMapping("/revenuecat")
    public ResponseEntity<Map<String, String>> handleRevenueCat(
            HttpServletRequest request,
            @RequestBody byte[] body) {

        // Verify authorization header
        String authHeader = request.getHeader("Authorization");
        String expectedToken = billingProps.getRevenuecat().getWebhookAuthorizationToken();
        if (expectedToken != null && !expectedToken.isBlank()) {
            String expectedAuth = expectedToken.startsWith("Bearer ") ? expectedToken : "Bearer " + expectedToken;
            if (authHeader == null || !authHeader.equals(expectedAuth)) {
                log.warn("RevenueCat webhook: invalid authorization header");
                return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
            }
        }

        try {
            revenueCatHandler.handle(body);
        } catch (Exception e) {
            log.error("RevenueCat webhook error: {}", e.getMessage());
        }

        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PostMapping("/chapa")
    public ResponseEntity<Map<String, String>> handleChapa(
            HttpServletRequest request,
            @RequestBody byte[] body) {

        // Verify Chapa webhook signature
        String chapaSignature = request.getHeader("Chapa-Signature");
        String webhookSecret = billingProps.getChapa().getWebhookSecret();
        if (webhookSecret != null && !webhookSecret.isBlank() && chapaSignature != null) {
            if (!verifyChapaSignature(body, chapaSignature, webhookSecret)) {
                log.warn("Chapa webhook: invalid signature");
                return ResponseEntity.status(401).body(Map.of("error", "invalid_signature"));
            }
        }

        try {
            chapaHandler.handle(body);
        } catch (Exception e) {
            log.error("Chapa webhook error: {}", e.getMessage());
        }

        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PostMapping("/verify-et")
    public ResponseEntity<Map<String, String>> handleVerifyEt(
            HttpServletRequest request,
            @RequestBody byte[] body) {

        String deliveryId = request.getHeader("X-Webhook-Delivery-Id");

        log.info("verify.et webhook received: deliveryId={}, bodySize={} bytes, payload={}",
                deliveryId, body.length, new String(body, java.nio.charset.StandardCharsets.UTF_8));

        try {
            verifyEtHandler.handle(body);
        } catch (Exception e) {
            log.error("verify.et webhook error: {}", e.getMessage(), e);
        }

        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    private boolean verifyChapaSignature(byte[] body, String signature, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(body);
            String computed = HexFormat.of().formatHex(hash);
            return computed.equalsIgnoreCase(signature);
        } catch (Exception e) {
            log.error("Chapa signature verification error: {}", e.getMessage());
            return false;
        }
    }

}
