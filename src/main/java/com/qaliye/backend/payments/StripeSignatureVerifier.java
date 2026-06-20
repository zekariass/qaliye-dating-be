package com.qaliye.backend.payments;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Component
public class StripeSignatureVerifier implements PaymentSignatureVerifier {

    @Value("${stripe.webhook-secret}")
    private String webhookSecret;

    @Override
    public boolean verify(HttpServletRequest request, byte[] body) {
        String sigHeader = request.getHeader("Stripe-Signature");
        if (sigHeader == null) return false;

        String timestamp = null;
        String expectedSig = null;
        for (String part : sigHeader.split(",")) {
            if (part.startsWith("t=")) timestamp = part.substring(2);
            else if (part.startsWith("v1=")) expectedSig = part.substring(3);
        }
        if (timestamp == null || expectedSig == null) return false;

        try {
            String payload = timestamp + "." + new String(body, StandardCharsets.UTF_8);
            String computed = hmacSha256Hex(webhookSecret, payload.getBytes(StandardCharsets.UTF_8));
            return computed.equals(expectedSig);
        } catch (Exception e) {
            return false;
        }
    }

    static String hmacSha256Hex(String secret, byte[] message) throws Exception {
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = hmac.doFinal(message);
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
