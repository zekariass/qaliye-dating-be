package com.qaliye.backend.billing.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaliye.backend.billing.BillingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Production-grade verify.et REST client.
 *
 * <p>Ref: https://verify.et/docs/api
 *
 * <p>POST /api/verify returns:
 * <ul>
 *   <li>200 – inline terminal result (processingStatus = completed/failed)</li>
 *   <li>202 – request queued; poll or wait for webhook</li>
 * </ul>
 */
@Component
public class VerifyEtClient {

    private static final Logger log = LoggerFactory.getLogger(VerifyEtClient.class);

    private static final String VERIFY_PATH = "/api/verify";
    private static final int WAIT_MS = 8000;

    private static final Set<String> SUPPORTED_BANKS = Set.of(
            "cbe", "telebirr", "cbebirr", "mpesa", "boa",
            "awash", "dashen", "siinqee", "kaafiebirr"
    );

    private final BillingProperties billingProps;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public VerifyEtClient(BillingProperties billingProps,
                          RestClient restClient,
                          ObjectMapper objectMapper) {
        this.billingProps = billingProps;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    // ── Response types ───────────────────────────────────────────────────────

    public record VerifyEtResponse(
            boolean queued,
            String requestId,
            String processingStatus,
            String status,
            boolean verified,
            VerifyEtResult result,
            VerifyEtError error
    ) {}

    public record VerifyEtResult(
            String bank,
            String amount,
            String currency,
            String referenceNumber,
            String accountSuffix,
            String transactionTimestamp,
            ConfirmationHistory confirmationHistory,
            SettlementAccountMatch settlementAccountMatch
    ) {}

    public record ConfirmationHistory(
            boolean confirmedBefore,
            int confirmationCount
    ) {}

    public record SettlementAccountMatch(
            boolean matched,
            boolean ambiguous,
            String matchType,
            String matchConfidence,
            String receiverAccount
    ) {}

    public record VerifyEtError(
            String code,
            String message,
            boolean retryable
    ) {}

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Submits a MANUAL_TRANSFER payment for verification.
     *
     * @param methodCode      The payment method code (= bank in verify.et)
     * @param userFields      Fields submitted by the user from the frontend
     * @param idempotencyKey  Stable per-order idempotency key
     * @param webhookUrl      Our webhook URL for async callbacks
     * @return verification response (queued=true means 202, false means 200 inline result)
     */
    @SuppressWarnings("unchecked")
    public VerifyEtResponse submit(String methodCode, Map<String, Object> userFields,
                                   String idempotencyKey, String webhookUrl) {
        BillingProperties.Verifier cfg = billingProps.getVerifier();
        String baseUrl = cfg.getBaseUrl();
        String apiKey = cfg.getApiKey();

        if (baseUrl == null || baseUrl.isBlank()) {
            throw new VerifyEtException("verify.et base URL is not configured", false);
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new VerifyEtException("verify.et API key is not configured", false);
        }
        if (!SUPPORTED_BANKS.contains(methodCode)) {
            throw new VerifyEtException("Bank not supported by verify.et: " + methodCode, false);
        }

        Map<String, Object> payload = buildPayload(methodCode, userFields, webhookUrl);
        String url = baseUrl + VERIFY_PATH + "?waitMs=" + WAIT_MS;

        String payloadJson;
        try { payloadJson = objectMapper.writeValueAsString(payload); }
        catch (Exception e) { payloadJson = payload.toString(); }
        log.info("Submitting verify.et request: bank={}, idempotencyKey={}, payload={}",
                methodCode, idempotencyKey, payloadJson);

        try {
            org.springframework.http.ResponseEntity<String> response = restClient.post()
                    .uri(url)
                    .header("x-api-key", apiKey)
                    .header("Idempotency-Key", idempotencyKey)
                    .header("x-webhook-url", webhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                        int httpStatus = resp.getStatusCode().value();
                        log.warn("verify.et 4xx for bank={}: HTTP {}", methodCode, httpStatus);
                        throw new VerifyEtException(
                                "verify.et returned HTTP " + httpStatus, false);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                        int httpStatus = resp.getStatusCode().value();
                        log.warn("verify.et 5xx for bank={}: HTTP {}", methodCode, httpStatus);
                        throw new VerifyEtException(
                                "verify.et returned HTTP " + httpStatus, true);
                    })
                    .toEntity(String.class);

            int httpStatus = response.getStatusCode().value();
            boolean isQueued = httpStatus == 202;
            String responseBody = response.getBody();

            if (responseBody == null || responseBody.isBlank()) {
                throw new VerifyEtException("Empty response from verify.et", true);
            }

            log.info("verify.et response HTTP {}: {}", httpStatus, responseBody);
            Map<String, Object> root = objectMapper.readValue(responseBody, Map.class);
            return parseResponse(root, isQueued);

        } catch (VerifyEtException e) {
            throw e;
        } catch (Exception e) {
            log.error("verify.et client error for bank={}: {}", methodCode, e.getMessage());
            throw new VerifyEtException("verify.et request failed: " + e.getMessage(), true);
        }
    }

    /**
     * Polls the status of a previously queued verify.et request.
     * Returns a response with queued=true if still processing, or the completed result.
     */
    @SuppressWarnings("unchecked")
    public VerifyEtResponse checkStatus(String verifyEtRequestId) {
        BillingProperties.Verifier cfg = billingProps.getVerifier();
        String baseUrl = cfg.getBaseUrl();
        String apiKey = cfg.getApiKey();

        if (baseUrl == null || baseUrl.isBlank()) {
            throw new VerifyEtException("verify.et base URL is not configured", false);
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new VerifyEtException("verify.et API key is not configured", false);
        }

        String url = baseUrl + VERIFY_PATH + "/" + verifyEtRequestId;
        log.info("Polling verify.et status: requestId={}", verifyEtRequestId);

        try {
            org.springframework.http.ResponseEntity<String> response = restClient.get()
                    .uri(url)
                    .header("x-api-key", apiKey)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                        int httpStatus = resp.getStatusCode().value();
                        log.warn("verify.et status 4xx for requestId={}: HTTP {}", verifyEtRequestId, httpStatus);
                        throw new VerifyEtException("verify.et status returned HTTP " + httpStatus, false);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                        int httpStatus = resp.getStatusCode().value();
                        log.warn("verify.et status 5xx for requestId={}: HTTP {}", verifyEtRequestId, httpStatus);
                        throw new VerifyEtException("verify.et status returned HTTP " + httpStatus, true);
                    })
                    .toEntity(String.class);

            String responseBody = response.getBody();
            if (responseBody == null || responseBody.isBlank()) {
                throw new VerifyEtException("Empty status response from verify.et", true);
            }

            log.info("verify.et status poll response for {}: {}", verifyEtRequestId, responseBody);
            Map<String, Object> root = objectMapper.readValue(responseBody, Map.class);
            VerifyEtResponse resp = parseResponse(root, false);
            // If still processing, mark as queued
            if ("queued".equals(resp.processingStatus()) || "running".equals(resp.processingStatus())) {
                return new VerifyEtResponse(true, resp.requestId(), resp.processingStatus(),
                        resp.status(), resp.verified(), null, resp.error());
            }
            return resp;

        } catch (VerifyEtException e) {
            throw e;
        } catch (Exception e) {
            log.error("verify.et status check error for requestId={}: {}", verifyEtRequestId, e.getMessage());
            throw new VerifyEtException("verify.et status check failed: " + e.getMessage(), true);
        }
    }

    // ── Payload builder ──────────────────────────────────────────────────────

    /**
     * Builds the bank-specific JSON body for /api/verify.
     * methodCode == bank field in verify.et.
     */
    private Map<String, Object> buildPayload(String methodCode, Map<String, Object> userFields, String webhookUrl) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bank", methodCode);
        if (webhookUrl != null && !webhookUrl.isBlank()) {
            payload.put("webhookUrl", webhookUrl);
        }

        Map<String, Object> fields = userFields != null ? new LinkedHashMap<>(userFields) : Map.of();

        switch (methodCode.toLowerCase()) {
            case "cbe" -> populateCbe(payload, fields);
            case "telebirr" -> populateTelebirr(payload, fields);
            case "cbebirr" -> populateCbeBirr(payload, fields);
            case "mpesa" -> populateMpesa(payload, fields);
            case "boa" -> populateBoa(payload, fields);
            case "awash" -> populateAwash(payload, fields);
            case "dashen" -> populateDashen(payload, fields);
            case "siinqee" -> populateSiinqee(payload, fields);
            case "kaafiebirr" -> populateKaafieBirr(payload, fields);
            default -> payload.putAll(fields);
        }
        return payload;
    }

    private void populateCbe(Map<String, Object> payload, Map<String, Object> fields) {
        Object receipt = firstNonNull(fields.get("receiptNumber"), fields.get("referenceNumber"), fields.get("reference"));
        if (receipt != null) payload.put("recieptNumber", receipt); // verify.et expects this key per spec
        // accountSuffix is optional for legacy FT references but can be forwarded if present
        Object suffix = fields.get("accountSuffix");
        if (suffix != null) payload.put("accountSuffix", suffix);
    }

    private void populateTelebirr(Map<String, Object> payload, Map<String, Object> fields) {
        Object ref = firstNonNull(fields.get("transactionOrReference"), fields.get("transactionNumber"), fields.get("reference"));
        if (ref != null) {
            payload.put("transactionNumber", ref);
            payload.put("reference", ref);
        }
    }

    private void populateCbeBirr(Map<String, Object> payload, Map<String, Object> fields) {
        Object ref = firstNonNull(fields.get("referenceNumber"), fields.get("reference"), fields.get("transactionNumber"));
        if (ref != null) payload.put("reference", ref);
        Object phone = firstNonNull(fields.get("phoneNumber"), fields.get("phone"));
        if (phone != null) payload.put("phoneNumber", phone);
    }

    private void populateMpesa(Map<String, Object> payload, Map<String, Object> fields) {
        Object ref = firstNonNull(fields.get("transactionNumber"), fields.get("referenceNumber"), fields.get("reference"));
        if (ref != null) payload.put("reference", ref);
    }

    private void populateBoa(Map<String, Object> payload, Map<String, Object> fields) {
        Object ref = firstNonNull(fields.get("referenceNumber"), fields.get("reference"));
        if (ref != null) payload.put("reference", ref);
        Object suffix = firstNonNull(fields.get("suffix"), fields.get("accountSuffix"));
        if (suffix != null) payload.put("suffix", suffix);
    }

    private void populateAwash(Map<String, Object> payload, Map<String, Object> fields) {
        Object ref = firstNonNull(fields.get("referenceNumber"), fields.get("reference"));
        if (ref != null) payload.put("reference", ref);
    }

    private void populateDashen(Map<String, Object> payload, Map<String, Object> fields) {
        Object ref = firstNonNull(fields.get("referenceNumber"), fields.get("reference"));
        if (ref != null) payload.put("reference", ref);
    }

    private void populateSiinqee(Map<String, Object> payload, Map<String, Object> fields) {
        Object ref = firstNonNull(fields.get("referenceNumber"), fields.get("reference"));
        if (ref != null) payload.put("reference", ref);
    }

    private void populateKaafieBirr(Map<String, Object> payload, Map<String, Object> fields) {
        Object ref = firstNonNull(fields.get("referenceNumber"), fields.get("reference"));
        if (ref != null) payload.put("reference", ref);
    }

    private static Object firstNonNull(Object... values) {
        if (values == null) return null;
        for (Object v : values) {
            if (v != null) return v;
        }
        return null;
    }

    // ── Response parser ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private VerifyEtResponse parseResponse(Map<String, Object> root, boolean isQueued) {
        Object requestIdRaw = root.get("requestId");
        String requestId = requestIdRaw != null ? String.valueOf(requestIdRaw) : null;

        // processingStatus/status/verified can be in "data" (webhook) or "verification" (API response)
        Map<String, Object> data = extractDataMap(root);
        Map<String, Object> verification = root.containsKey("verification")
                ? (Map<String, Object>) root.get("verification")
                : Map.of();

        Object psRaw = firstNonNull(data.get("processingStatus"), verification.get("processingStatus"));
        String processingStatus = psRaw != null ? String.valueOf(psRaw) : (isQueued ? "queued" : "unknown");
        Object stRaw = firstNonNull(data.get("status"), verification.get("status"));
        String status = stRaw != null ? String.valueOf(stRaw) : (isQueued ? "pending" : "unknown");
        boolean verified = Boolean.TRUE.equals(data.get("verified")) || Boolean.TRUE.equals(verification.get("verified"));

        VerifyEtResult result = null;
        if (!isQueued) {
            result = parseResult(data);
        }

        VerifyEtError error = null;
        Map<String, Object> errorMap = (Map<String, Object>) root.get("error");
        if (errorMap != null) {
            error = new VerifyEtError(
                    errorMap.get("code") != null ? String.valueOf(errorMap.get("code")) : null,
                    errorMap.get("message") != null ? String.valueOf(errorMap.get("message")) : null,
                    Boolean.TRUE.equals(errorMap.get("retryable"))
            );
        }

        return new VerifyEtResponse(isQueued, requestId, processingStatus, status, verified, result, error);
    }

    @SuppressWarnings("unchecked")
    private VerifyEtResult parseResult(Map<String, Object> data) {
        ConfirmationHistory confirmationHistory = null;
        Map<String, Object> chMap = (Map<String, Object>) data.get("confirmationHistory");
        if (chMap != null) {
            confirmationHistory = new ConfirmationHistory(
                    Boolean.TRUE.equals(chMap.get("confirmedBefore")),
                    chMap.get("confirmationCount") instanceof Number n ? n.intValue() : 0
            );
        }

        SettlementAccountMatch settlementAccountMatch = null;
        Map<String, Object> samMap = (Map<String, Object>) data.get("settlementAccountMatch");
        if (samMap != null) {
            settlementAccountMatch = new SettlementAccountMatch(
                    Boolean.TRUE.equals(samMap.get("matched")),
                    Boolean.TRUE.equals(samMap.get("ambiguous")),
                    samMap.get("matchType") != null ? String.valueOf(samMap.get("matchType")) : null,
                    samMap.get("matchConfidence") != null ? String.valueOf(samMap.get("matchConfidence")) : null,
                    samMap.get("receiverAccount") != null ? String.valueOf(samMap.get("receiverAccount")) : null
            );
        }

        Object bankRaw = data.get("bank");
        Object amountRaw = data.get("amount");
        Object currencyRaw = data.get("currency");
        if (currencyRaw == null) {
            Map<String, Object> bankSpecific = (Map<String, Object>) data.get("bankSpecific");
            if (bankSpecific != null) currencyRaw = bankSpecific.get("currency");
        }
        Object refRaw = firstNonNull(
                data.get("referenceNumber"),
                data.get("receiptNumber"),
                data.get("transactionNumber"));
        Object suffixRaw = data.get("accountSuffix");
        Object tsRaw = data.get("timestamp");

        return new VerifyEtResult(
                bankRaw != null ? String.valueOf(bankRaw) : null,
                amountRaw != null ? String.valueOf(amountRaw) : null,
                currencyRaw != null ? String.valueOf(currencyRaw) : "ETB",
                refRaw != null ? String.valueOf(refRaw) : null,
                suffixRaw != null ? String.valueOf(suffixRaw) : null,
                tsRaw != null ? String.valueOf(tsRaw) : null,
                confirmationHistory,
                settlementAccountMatch
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractDataMap(Map<String, Object> root) {
        Object dataObj = root.get("data");
        if (dataObj instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        } else if (dataObj instanceof java.util.List<?> list && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?>) {
            return (Map<String, Object>) list.get(0);
        }
        return Map.of();
    }

    // ── Exception ────────────────────────────────────────────────────────────

    public static class VerifyEtException extends RuntimeException {
        private final boolean retryable;

        public VerifyEtException(String message, boolean retryable) {
            super(message);
            this.retryable = retryable;
        }

        public boolean isRetryable() {
            return retryable;
        }
    }
}
