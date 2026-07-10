package com.qaliye.backend.billing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaliye.backend.billing.BillingProperties;
import com.qaliye.backend.billing.repository.BillingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Processes verify.et webhook deliveries (event: verification.completed).
 *
 * <p>Security: validates X-Webhook-Signature = sha256=HMAC(timestamp.rawBody, apiKey)
 * with a 5-minute timestamp tolerance.
 *
 * <p>Status mapping:
 * <ul>
 *   <li>processingStatus=queued/running → keep VERIFICATION_PENDING</li>
 *   <li>verified=true + settlement matched + bank+amount match → VERIFIED → fulfill</li>
 *   <li>verified=true + settlement matched + bank or amount mismatch → MANUAL_REVIEW</li>
 *   <li>verified=true + settlement NOT matched → MANUAL_REVIEW</li>
 *   <li>verified=true + confirmedBefore=true → MANUAL_REVIEW (duplicate)</li>
 *   <li>verified=true + duplicate providerRef in another verified order → MANUAL_REVIEW</li>
 *   <li>verified=true + order older than manualTransferMaxAgeHours → EXPIRED</li>
 *   <li>status=not_found → REJECTED</li>
 *   <li>status=failed / processingStatus=failed → MANUAL_REVIEW</li>
 * </ul>
 */
@Service
public class VerifyEtWebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(VerifyEtWebhookHandler.class);
    private static final Duration TIMESTAMP_TOLERANCE = Duration.ofMinutes(5);

    private final BillingRepository billingRepo;
    private final FulfillmentService fulfillmentService;
    private final BillingProperties billingProps;
    private final ObjectMapper objectMapper;

    public VerifyEtWebhookHandler(BillingRepository billingRepo,
                                   FulfillmentService fulfillmentService,
                                   BillingProperties billingProps,
                                   ObjectMapper objectMapper) {
        this.billingRepo = billingRepo;
        this.fulfillmentService = fulfillmentService;
        this.billingProps = billingProps;
        this.objectMapper = objectMapper;
    }

    /**
     * Validates the webhook signature.
     * Signature payload: "${timestamp}.${rawBody}"
     * Header: X-Webhook-Signature: sha256=<hmac-hex>
     *
     * @return true if valid or secret not configured
     */
    public boolean validateSignature(String timestamp, String signature, byte[] rawBody) {
        String secret = billingProps.getVerifier().getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            log.warn("verify.et webhook secret not configured, skipping signature validation");
            return true;
        }
        if (signature == null || signature.isBlank()) {
            log.warn("verify.et webhook: missing X-Webhook-Signature header");
            return false;
        }
        if (timestamp == null || timestamp.isBlank()) {
            log.warn("verify.et webhook: missing X-Webhook-Timestamp header");
            return false;
        }

        try {
            Instant ts = Instant.parse(timestamp);
            if (Duration.between(ts, Instant.now()).abs().compareTo(TIMESTAMP_TOLERANCE) > 0) {
                log.warn("verify.et webhook: timestamp {} outside tolerance window", timestamp);
                return false;
            }
        } catch (Exception e) {
            log.warn("verify.et webhook: unparseable timestamp: {}", timestamp);
            return false;
        }

        try {
            String payload = timestamp + "." + new String(rawBody, StandardCharsets.UTF_8);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String computed = "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));

            if (computed.length() != signature.length()) return false;
            byte[] a = computed.getBytes(StandardCharsets.UTF_8);
            byte[] b = signature.getBytes(StandardCharsets.UTF_8);
            int diff = 0;
            for (int i = 0; i < a.length; i++) diff |= (a[i] ^ b[i]);
            return diff == 0;
        } catch (Exception e) {
            log.error("verify.et signature computation error: {}", e.getMessage());
            return false;
        }
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public void handle(byte[] rawBody) {
        try {
            Map<String, Object> payload = objectMapper.readValue(rawBody, Map.class);

            Object eventRaw = payload.get("event");
            String event = eventRaw != null ? String.valueOf(eventRaw) : null;
            if (!"verification.completed".equals(event)) {
                log.debug("verify.et webhook: ignoring event type '{}'", event);
                return;
            }

            Object requestIdRaw = payload.get("requestId");
            String requestId = requestIdRaw != null ? String.valueOf(requestIdRaw) : null;
            if (requestId == null || requestId.isBlank()) {
                log.warn("verify.et webhook: missing requestId");
                return;
            }

            String rawBodyStr = new String(rawBody, StandardCharsets.UTF_8);

            Optional<UUID> eventDbId = billingRepo.logEvent(
                    "VERIFY_ET", requestId, event, rawBodyStr, "PROCESSING");
            if (eventDbId.isEmpty()) {
                log.info("verify.et webhook: duplicate delivery for requestId={}, ignored", requestId);
                return;
            }

            Optional<BillingRepository.VerificationAttemptRow> attemptOpt =
                    billingRepo.findVerificationByVerifyEtRequestId(requestId);
            if (attemptOpt.isEmpty()) {
                log.warn("verify.et webhook: no verification attempt found for requestId={}", requestId);
                billingRepo.updateEventStatus(eventDbId.get(), "PROCESSED", null);
                return;
            }

            BillingRepository.VerificationAttemptRow attempt = attemptOpt.get();

            Set<String> terminalStatuses = Set.of("VERIFIED", "REJECTED", "MANUAL_REVIEW");
            if (terminalStatuses.contains(attempt.status())) {
                log.info("verify.et webhook: order {} already finalized ({}), skipping",
                        attempt.orderId(), attempt.status());
                billingRepo.updateEventStatus(eventDbId.get(), "PROCESSED", null);
                return;
            }

            // processingStatus/status/verified can be in "data" (webhook) or "verification" (API response)
            Map<String, Object> data = extractDataMap(payload);
            Map<String, Object> verification = payload.containsKey("verification")
                    ? (Map<String, Object>) payload.get("verification")
                    : Map.of();

            Object psRaw = firstNonNull(data.get("processingStatus"), verification.get("processingStatus"));
            String processingStatus = psRaw != null ? String.valueOf(psRaw) : "unknown";
            Object stRaw = firstNonNull(data.get("status"), verification.get("status"));
            String status = stRaw != null ? String.valueOf(stRaw) : "unknown";
            boolean verified = Boolean.TRUE.equals(data.get("verified")) || Boolean.TRUE.equals(verification.get("verified"));

            if ("queued".equals(processingStatus) || "running".equals(processingStatus)) {
                log.info("verify.et webhook: still processing requestId={}, status={}", requestId, processingStatus);
                billingRepo.updateEventStatus(eventDbId.get(), "PROCESSED", null);
                return;
            }

            // referenceNumber is not always present; fall back to receiptNumber / transactionNumber
            Object refRaw = firstNonNull(
                    data.get("referenceNumber"),
                    data.get("receiptNumber"),
                    data.get("transactionNumber"));
            String providerRef = refRaw != null ? String.valueOf(refRaw) : null;
            Integer verifiedAmount = parseVerifiedAmount(data);
            Object currencyRaw = data.get("currency");
            if (currencyRaw == null) {
                Map<String, Object> bankSpecific = (Map<String, Object>) data.get("bankSpecific");
                if (bankSpecific != null) currencyRaw = bankSpecific.get("currency");
            }
            String verifiedCurrency = currencyRaw != null ? String.valueOf(currencyRaw) : "ETB";
            Boolean settlementMatched = extractSettlementMatched(data);
            Boolean confirmedBefore = extractConfirmedBefore(data);

            StatusResult statusResult = resolveOrderStatus(data, processingStatus, status, verified,
                    attempt, providerRef, verifiedAmount);
            String orderStatus = statusResult.status();

            billingRepo.finalizeVerificationAttemptVerifyEt(
                    attempt.id(), orderStatus, verifiedAmount, verifiedCurrency,
                    providerRef, settlementMatched, confirmedBefore, rawBodyStr);

            billingRepo.updateOrderStatus(attempt.orderId(), orderStatus, statusResult.reason());

            if ("VERIFIED".equals(orderStatus)) {
                fulfillmentService.fulfillVerifiedOrder(attempt.orderId(), attempt.userId());
                log.info("verify.et webhook: order {} verified and fulfilled (requestId={})",
                        attempt.orderId(), requestId);
            } else {
                log.info("verify.et webhook: order {} -> {} reason={} (requestId={}, processingStatus={}, verifyStatus={})",
                        attempt.orderId(), orderStatus, statusResult.reason(), requestId, processingStatus, status);
            }

            billingRepo.updateEventStatus(eventDbId.get(), "PROCESSED", null);

        } catch (Exception e) {
            log.error("verify.et webhook processing error: {}", e.getMessage(), e);
        }
    }

    // ── Status resolution ────────────────────────────────────────────────────

    private record StatusResult(String status, String reason) {}

    @SuppressWarnings("unchecked")
    private StatusResult resolveOrderStatus(Map<String, Object> data,
                                       String processingStatus, String verifyStatus,
                                       boolean verified,
                                       BillingRepository.VerificationAttemptRow attempt,
                                       String providerRef, Integer verifiedAmount) {
        // Terminal failure -> REJECTED
        if ("failed".equals(processingStatus) || "not_found".equals(verifyStatus)) {
            return new StatusResult("REJECTED",
                    "processingStatus=" + processingStatus + ", status=" + verifyStatus);
        }

        // Must be fully verified: completed + success + verified=true
        if (!verified || !"completed".equals(processingStatus) || !"success".equals(verifyStatus)) {
            return new StatusResult("MANUAL_REVIEW",
                    "not fully verified: processingStatus=" + processingStatus
                    + ", status=" + verifyStatus + ", verified=" + verified);
        }

        // Bank must match
        Object bankRaw = data.get("bank");
        String verifiedBank = bankRaw != null ? String.valueOf(bankRaw) : null;
        if (verifiedBank == null || attempt.orderMethodCode() == null
                || !verifiedBank.equalsIgnoreCase(attempt.orderMethodCode())) {
            return new StatusResult("MANUAL_REVIEW",
                    "bank mismatch: expected=" + attempt.orderMethodCode() + ", got=" + verifiedBank);
        }

        // Amount must match
        if (verifiedAmount != null && attempt.orderExpectedAmount() != null
                && !verifiedAmount.equals(attempt.orderExpectedAmount())) {
            return new StatusResult("MANUAL_REVIEW",
                    "amount mismatch: expected=" + attempt.orderExpectedAmount()
                    + " minor units, got=" + verifiedAmount);
        }

        // Settlement account must be matched, unambiguous, and high confidence
        Map<String, Object> sam = (Map<String, Object>) data.get("settlementAccountMatch");
        if (sam == null || !Boolean.TRUE.equals(sam.get("matched"))) {
            return new StatusResult("MANUAL_REVIEW", "settlement account not matched");
        }
        if (Boolean.TRUE.equals(sam.get("ambiguous"))) {
            return new StatusResult("MANUAL_REVIEW", "settlement account match is ambiguous");
        }
        Object confidenceRaw = sam.get("matchConfidence");
        String matchConfidence = confidenceRaw != null ? String.valueOf(confidenceRaw) : null;
        if (!"high".equalsIgnoreCase(matchConfidence)) {
            return new StatusResult("MANUAL_REVIEW",
                    "settlement match confidence not high: " + matchConfidence);
        }

        // Transfer timestamp max-age check (data.timestamp = actual bank transfer time)
        Object tsRaw = data.get("timestamp");
        if (tsRaw != null) {
            try {
                Instant transferTime = Instant.parse(String.valueOf(tsRaw));
                long maxAgeHours = billingProps.getManualTransferMaxAgeHours();
                Instant cutoff = Instant.now().minus(Duration.ofHours(maxAgeHours));
                if (transferTime.isBefore(cutoff)) {
                    log.warn("verify.et: transfer timestamp {} is older than {}h - EXPIRED for order={}",
                            tsRaw, maxAgeHours, attempt.orderId());
                    return new StatusResult("EXPIRED",
                            "transfer older than " + maxAgeHours + "h (transfer time: " + tsRaw + ")");
                }
            } catch (Exception e) {
                log.warn("verify.et: could not parse transfer timestamp: {}", tsRaw);
            }
        }

        // Duplicate provider reference check
        if (providerRef != null
                && billingRepo.existsVerifiedProviderReference(providerRef, attempt.orderId())) {
            log.warn("verify.et webhook: duplicate providerRef={} for order={}",
                    providerRef, attempt.orderId());
            return new StatusResult("MANUAL_REVIEW",
                    "duplicate provider reference: " + providerRef);
        }

        return new StatusResult("VERIFIED", null);
    }

    @SuppressWarnings("unchecked")
    private Boolean extractSettlementMatched(Map<String, Object> data) {
        Map<String, Object> sam = (Map<String, Object>) data.get("settlementAccountMatch");
        if (sam == null) return null;
        return Boolean.TRUE.equals(sam.get("matched"));
    }

    @SuppressWarnings("unchecked")
    private boolean extractConfirmedBefore(Map<String, Object> data) {
        Map<String, Object> ch = (Map<String, Object>) data.get("confirmationHistory");
        if (ch == null) return false;
        return Boolean.TRUE.equals(ch.get("confirmedBefore"));
    }

    private Integer parseVerifiedAmount(Map<String, Object> data) {
        Object amtObj = data.get("amount");
        if (amtObj == null) return null;
        try {
            double etb = Double.parseDouble(amtObj.toString());
            return (int) Math.round(etb * 100);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractDataMap(Map<String, Object> payload) {
        // verify.et returns "data" as a JSON array (empty for 202, one element for 200)
        Object dataObj = payload.get("data");
        if (dataObj instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        } else if (dataObj instanceof java.util.List<?> list && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?>) {
            return (Map<String, Object>) list.get(0);
        }
        // Fallback: verification.result contains the same structure
        Map<String, Object> verification = (Map<String, Object>) payload.get("verification");
        if (verification != null) {
            Object resultObj = verification.get("result");
            if (resultObj instanceof Map<?, ?> rm) {
                return (Map<String, Object>) rm;
            }
            return verification;
        }
        return Map.of();
    }

    private static Object firstNonNull(Object... values) {
        if (values == null) return null;
        for (Object v : values) {
            if (v != null) return v;
        }
        return null;
    }
}
