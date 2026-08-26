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
        // Per Chapa docs: both chapa-signature and x-chapa-signature headers may be present;
        // if either is valid, proceed. HMAC is signed with the secret key.
        String chapaSignature = request.getHeader("Chapa-Signature");
        String xChapaSignature = request.getHeader("x-chapa-signature");
        String secretKey = billingProps.getChapa().getSecretKey();
        String webhookSecret = billingProps.getChapa().getWebhookSecret();
        // Trim to remove any trailing whitespace/newline from env vars
        if (secretKey != null) secretKey = secretKey.trim();
        if (webhookSecret != null) webhookSecret = webhookSecret.trim();
        // Prefer webhook secret if configured, otherwise use the API secret key
        String verifySecret = (webhookSecret != null && !webhookSecret.isBlank()) ? webhookSecret : secretKey;

        if (verifySecret != null && !verifySecret.isBlank()) {
            // Try verification with both the webhook secret and the API secret key
            // Chapa docs are ambiguous about which key is used for signing
            boolean chapaValid = false;
            boolean xChapaValid = false;

            if (chapaSignature != null) {
                chapaValid = verifySecretSignature(body, chapaSignature, verifySecret);
                if (!chapaValid && secretKey != null && !secretKey.isBlank() && !secretKey.equals(verifySecret)) {
                    chapaValid = verifySecretSignature(body, chapaSignature, secretKey);
                }
            }
            if (xChapaSignature != null) {
                xChapaValid = verifyChapaSignature(body, xChapaSignature, verifySecret);
                if (!xChapaValid && secretKey != null && !secretKey.isBlank() && !secretKey.equals(verifySecret)) {
                    xChapaValid = verifyChapaSignature(body, xChapaSignature, secretKey);
                }
            }

            if (!chapaValid && !xChapaValid) {
                log.warn("Chapa webhook: no valid signature found (chapa-signature={}, x-chapa-signature={})",
                        chapaSignature != null, xChapaSignature != null);
                log.debug("Chapa webhook debug: webhookSecretSet={}, secretKeySet={}, bodyPreview={}",
                        webhookSecret != null && !webhookSecret.isBlank(),
                        secretKey != null && !secretKey.isBlank(),
                        body.length > 200 ? new String(body, 0, 200, StandardCharsets.UTF_8) + "..." : new String(body, StandardCharsets.UTF_8));
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
        String timestamp = request.getHeader("X-Webhook-Timestamp");
        String signature = request.getHeader("X-Webhook-Signature");

        log.info("verify.et webhook received: deliveryId={}, bodySize={} bytes", deliveryId, body.length);
        log.debug("verify.et webhook payload: {}", new String(body, java.nio.charset.StandardCharsets.UTF_8));

        if (!verifyEtHandler.validateSignature(timestamp, signature, body)) {
            log.warn("verify.et webhook: invalid signature, rejecting (deliveryId={})", deliveryId);
            return ResponseEntity.status(401).body(Map.of("error", "invalid_signature"));
        }

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
            boolean match = computed.equalsIgnoreCase(signature);
            if (!match) {
                log.debug("Chapa x-signature mismatch: computed={}, received={}, secretLen={}, bodyLen={}",
                        computed, signature, secret.length(), body.length);
            }
            return match;
        } catch (Exception e) {
            log.error("Chapa signature verification error: {}", e.getMessage());
            return false;
        }
    }

    private boolean verifySecretSignature(byte[] body, String signature, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(body);
            String computed = HexFormat.of().formatHex(hash);
            boolean match = computed.equalsIgnoreCase(signature);
            if (!match) {
                log.debug("Chapa signature mismatch: computed={}, received={}, secretLen={}, bodyLen={}",
                        computed, signature, secret.length(), body.length);
            }
            return match;
        } catch (Exception e) {
            log.error("Chapa secret signature verification error: {}", e.getMessage());
            return false;
        }
    }

}
