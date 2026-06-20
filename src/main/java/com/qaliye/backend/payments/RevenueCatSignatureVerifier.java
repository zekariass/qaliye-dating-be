package com.qaliye.backend.payments;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
public class RevenueCatSignatureVerifier implements PaymentSignatureVerifier {

    @Value("${revenuecat.webhook-secret}")
    private String webhookSecret;

    @Override
    public boolean verify(HttpServletRequest request, byte[] body) {
        String sigHeader = request.getHeader("X-RevenueCat-Signature");
        if (sigHeader == null) return false;

        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = hmac.doFinal(body);

            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            String computedHex = sb.toString();
            String computedBase64 = Base64.getEncoder().encodeToString(hash);

            String candidate = sigHeader.startsWith("sha256=")
                    ? sigHeader.substring(7) : sigHeader;

            return computedHex.equals(candidate) || computedBase64.equals(candidate);
        } catch (Exception e) {
            return false;
        }
    }
}
