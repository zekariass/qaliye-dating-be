package com.qaliye.backend.auth.hook;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/**
 * Verifies incoming Supabase hook requests using the Standard Webhooks specification
 * (https://www.standardwebhooks.com/).
 *
 * <p>The configured secret has the form {@code v1,whsec_<base64-key>}.
 * Signature verification uses HMAC-SHA256 over the signed content
 * {@code {webhook-id}.{webhook-timestamp}.{raw-body}}.
 */
@Component
public class SupabaseHookVerifier {

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final String WHSEC_PREFIX = "whsec_";
    private static final String SIG_VERSION_PREFIX = "v1,";
    private static final long TOLERANCE_SECONDS = 300;

    /**
     * Verifies the Standard Webhooks signature.
     *
     * @param rawBody          raw request body bytes
     * @param webhookId        value of the {@code webhook-id} header
     * @param webhookTimestamp value of the {@code webhook-timestamp} header (Unix seconds)
     * @param webhookSignature value of the {@code webhook-signature} header
     * @param secret           configured hook secret ({@code v1,whsec_<base64>})
     * @return {@code true} if the signature is valid and the timestamp is within tolerance
     */
    public boolean verify(byte[] rawBody,
                          String webhookId,
                          String webhookTimestamp,
                          String webhookSignature,
                          String secret) {
        if (rawBody == null || isBlank(webhookId) || isBlank(webhookTimestamp)
                || isBlank(webhookSignature) || isBlank(secret)) {
            return false;
        }

        if (!isTimestampFresh(webhookTimestamp)) {
            return false;
        }

        byte[] keyBytes = extractKeyBytes(secret);
        if (keyBytes == null) {
            return false;
        }

        byte[] computedHmac = computeHmac(webhookId, webhookTimestamp, rawBody, keyBytes);
        if (computedHmac == null) {
            return false;
        }

        String computedSig = Base64.getEncoder().encodeToString(computedHmac);

        for (String entry : webhookSignature.split(" ")) {
            String trimmed = entry.trim();
            if (trimmed.startsWith(SIG_VERSION_PREFIX)) {
                String sigValue = trimmed.substring(SIG_VERSION_PREFIX.length());
                if (MessageDigest.isEqual(
                        sigValue.getBytes(StandardCharsets.UTF_8),
                        computedSig.getBytes(StandardCharsets.UTF_8))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isTimestampFresh(String webhookTimestamp) {
        try {
            long ts = Long.parseLong(webhookTimestamp);
            long nowSeconds = Instant.now().getEpochSecond();
            return Math.abs(nowSeconds - ts) <= TOLERANCE_SECONDS;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private byte[] extractKeyBytes(String secret) {
        String keyPart = secret.trim();
        int commaIdx = keyPart.indexOf(',');
        if (commaIdx >= 0) {
            keyPart = keyPart.substring(commaIdx + 1);
        }
        if (keyPart.startsWith(WHSEC_PREFIX)) {
            keyPart = keyPart.substring(WHSEC_PREFIX.length());
        }
        try {
            return Base64.getDecoder().decode(keyPart);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private byte[] computeHmac(String webhookId, String webhookTimestamp,
                                byte[] rawBody, byte[] keyBytes) {
        String signedContent = webhookId + "." + webhookTimestamp + "."
                + new String(rawBody, StandardCharsets.UTF_8);
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(keyBytes, HMAC_ALGO));
            return mac.doFinal(signedContent.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
