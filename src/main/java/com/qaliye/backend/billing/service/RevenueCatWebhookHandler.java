package com.qaliye.backend.billing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaliye.backend.billing.repository.BillingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class RevenueCatWebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(RevenueCatWebhookHandler.class);

    private final BillingRepository billingRepo;
    private final FulfillmentService fulfillmentService;
    private final ObjectMapper objectMapper;
    private final CacheManager cacheManager;

    public RevenueCatWebhookHandler(BillingRepository billingRepo,
                                     FulfillmentService fulfillmentService,
                                     ObjectMapper objectMapper,
                                     CacheManager cacheManager) {
        this.billingRepo = billingRepo;
        this.fulfillmentService = fulfillmentService;
        this.objectMapper = objectMapper;
        this.cacheManager = cacheManager;
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public void handle(byte[] body) {
        UUID eventDbId = null;
        try {
            Map<String, Object> payload = objectMapper.readValue(body, Map.class);
            Map<String, Object> event = (Map<String, Object>) payload.get("event");
            if (event == null) {
                log.warn("RevenueCat webhook: no event object");
                return;
            }

            String eventType = (String) event.get("type");
            String appUserId = (String) event.get("app_user_id");
            String transactionId = extractTransactionId(event);
            String originalTransactionId = extractOriginalTransactionId(event);
            String productId = (String) event.get("product_id");
            String currency = (String) event.get("currency");
            Integer amountMinorUnits = extractAmountMinorUnits(event);

            if (appUserId == null) {
                log.warn("RevenueCat webhook: no app_user_id");
                return;
            }

            UUID userId;
            try {
                userId = UUID.fromString(appUserId);
            } catch (IllegalArgumentException e) {
                log.warn("RevenueCat webhook: invalid app_user_id={}", appUserId);
                return;
            }

            // Lock the user row BEFORE any payment_events insert to prevent FK-induced
            // KEY SHARE lock from deadlocking with concurrent FOR UPDATE attempts.
            billingRepo.lockUserRowForUpdate(userId);

            // Idempotency: use RevenueCat event "id" field, otherwise transaction_id, then original_transaction_id
            String eventId = extractEventId(event, transactionId, originalTransactionId);

            // Check if event was already processed successfully
            Optional<BillingRepository.EventStatusRow> existingEvent =
                    billingRepo.findEventStatus("REVENUECAT", eventId);
            if (existingEvent.isPresent()) {
                if ("PROCESSED".equals(existingEvent.get().processingStatus())) {
                    log.info("RevenueCat event already processed: eventId={}", eventId);
                    return;
                }
                // Event exists but failed or is processing — update to PROCESSING and retry
                eventDbId = existingEvent.get().id();
                billingRepo.updateEventStatus(eventDbId, "PROCESSING", null);
            } else {
                // Record new event with enriched metadata
                Optional<UUID> newEventId = billingRepo.logEvent("REVENUECAT", eventId,
                        eventType != null ? eventType : "UNKNOWN",
                        new String(body), "PROCESSING",
                        userId, amountMinorUnits, currency, Instant.now());
                if (newEventId.isEmpty()) {
                    log.info("RevenueCat duplicate event ignored: eventId={}", eventId);
                    return;
                }
                eventDbId = newEventId.get();
            }

            // Stable subscription identity: original_transaction_id (constant across renewals)
            // Current transaction reference: transaction_id (changes per renewal)
            String stableSubId = originalTransactionId != null ? originalTransactionId : transactionId;
            String providerSubRef = transactionId;

            UUID subscriptionId = null;
            UUID transactionDbId = null;

            switch (eventType != null ? eventType : "") {
                case "INITIAL_PURCHASE", "RENEWAL", "PRODUCT_CHANGE" -> {
                    var result = handleSubscriptionActive(userId, event, productId, stableSubId, providerSubRef,
                            "INITIAL_PURCHASE".equals(eventType) ? "PURCHASE" :
                                    "RENEWAL".equals(eventType) ? "RENEWAL" : "UPGRADE");
                    if (result != null) {
                        subscriptionId = result.subscriptionId();
                        transactionDbId = result.transactionId();
                    }
                }
                case "CANCELLATION" -> handleCancellation(userId, event, stableSubId);
                case "EXPIRATION" -> handleExpiration(userId, event, stableSubId);
                case "BILLING_ISSUE" -> handleBillingIssue(userId, event, stableSubId);
                case "SUBSCRIBER_ALIAS" -> log.info("RevenueCat alias event for user={}", userId);
                case "NON_RENEWING_PURCHASE" -> handleNonRenewingPurchase(userId, event, productId, transactionId);
                default -> log.info("RevenueCat unhandled event type={} for user={}", eventType, userId);
            }

            // Link event to subscription and transaction, then mark as processed
            if (subscriptionId != null || transactionDbId != null) {
                billingRepo.updateEventLinks(eventDbId, subscriptionId, transactionDbId);
            }
            billingRepo.updateEventStatus(eventDbId, "PROCESSED", null);

            evictSubscriptionCache(userId);
        } catch (Exception e) {
            log.error("RevenueCat webhook processing error: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    private record FulfillmentResult(UUID subscriptionId, UUID transactionId) {}

    @SuppressWarnings("unchecked")
    private FulfillmentResult handleSubscriptionActive(UUID userId, Map<String, Object> event,
                                          String productId, String stableSubId,
                                          String providerSubRef, String transactionType) {
        if (productId == null) {
            log.warn("RevenueCat: no product_id in subscription event for user={}", userId);
            return null;
        }

        Optional<BillingRepository.FullOfferRow> offerOpt = billingRepo.findOfferByExternalProductId(productId);
        if (offerOpt.isEmpty()) {
            log.warn("RevenueCat: no matching offer for productId={}", productId);
            return null;
        }

        BillingRepository.FullOfferRow offer = offerOpt.get();

        Instant periodStart = parseTimestampMs(event, "purchased_at_ms");
        if (periodStart == null) periodStart = parseTimestamp(event, "purchase_date");
        Instant periodEnd = parseTimestampMs(event, "expiration_at_ms");
        if (periodEnd == null) periodEnd = parseTimestamp(event, "expiration_date");

        if (periodStart == null) periodStart = Instant.now();
        if (periodEnd == null) periodEnd = periodStart.plusSeconds(30L * 24 * 3600);

        String store = (String) event.get("store");
        String provider = mapStoreToProvider(store);

        if (offer.subscriptionProductId() != null) {
            UUID subId = fulfillmentService.fulfillRevenueCatSubscription(
                    userId, stableSubId, providerSubRef, offer.planId(), offer.id(),
                    periodStart, periodEnd, provider, true, transactionType
            );
            UUID txId = billingRepo.findTransactionByProviderTxId(provider, providerSubRef).orElse(null);
            return new FulfillmentResult(subId, txId);
        } else if (offer.consumableProductId() != null) {
            fulfillmentService.fulfillRevenueCatConsumable(
                    userId, offer.entitlementType(), offer.quantityGranted(),
                    null, providerSubRef, offer.expiresAfterDays()
            );
            return null;
        }
        return null;
    }

    private void handleNonRenewingPurchase(UUID userId, Map<String, Object> event,
                                           String productId, String transactionId) {
        if (productId == null) return;

        Optional<BillingRepository.FullOfferRow> offerOpt = billingRepo.findOfferByExternalProductId(productId);
        if (offerOpt.isEmpty()) {
            log.warn("RevenueCat: no matching offer for non-renewing productId={}", productId);
            return;
        }

        BillingRepository.FullOfferRow offer = offerOpt.get();

        if (offer.consumableProductId() != null) {
            String providerSubId = transactionId != null ? transactionId : productId + "-" + UUID.randomUUID();
            fulfillmentService.fulfillRevenueCatConsumable(
                    userId, offer.entitlementType(), offer.quantityGranted(),
                    null, providerSubId, offer.expiresAfterDays()
            );
        }
    }

    private void handleCancellation(UUID userId, Map<String, Object> event, String stableSubId) {
        if (stableSubId != null) {
            billingRepo.cancelSubscription(stableSubId);
        }
        log.info("RevenueCat cancellation processed for user={}", userId);
    }

    private void handleExpiration(UUID userId, Map<String, Object> event, String stableSubId) {
        if (stableSubId != null) {
            billingRepo.updateSubscriptionStatus(stableSubId, "EXPIRED");
        }
        log.info("RevenueCat expiration processed for user={}", userId);
    }

    private void handleBillingIssue(UUID userId, Map<String, Object> event, String stableSubId) {
        if (stableSubId != null) {
            billingRepo.updateSubscriptionStatus(stableSubId, "PAST_DUE");
        }
        log.info("RevenueCat billing issue for user={}", userId);
    }

    private String extractTransactionId(Map<String, Object> event) {
        Object tid = event.get("transaction_id");
        return tid != null ? tid.toString() : null;
    }

    private String extractOriginalTransactionId(Map<String, Object> event) {
        Object oid = event.get("original_transaction_id");
        return oid != null ? oid.toString() : null;
    }

    private String extractEventId(Map<String, Object> event, String fallbackTransactionId,
                                   String fallbackOriginalTransactionId) {
        // RevenueCat uses "id" as the event identifier
        Object eid = event.get("id");
        if (eid != null) return eid.toString();
        Object rcEventId = event.get("event_id");
        if (rcEventId != null) return rcEventId.toString();
        if (fallbackTransactionId != null) return fallbackTransactionId;
        if (fallbackOriginalTransactionId != null) return fallbackOriginalTransactionId;
        return UUID.randomUUID().toString();
    }

    private Integer extractAmountMinorUnits(Map<String, Object> event) {
        Object price = event.get("price");
        if (price == null) return null;
        try {
            if (price instanceof Number n) {
                return (int) Math.round(n.doubleValue() * 100);
            }
            if (price instanceof String s) {
                return (int) Math.round(Double.parseDouble(s) * 100);
            }
        } catch (Exception e) {
            log.debug("Failed to parse price: {}", price);
        }
        return null;
    }

    private String mapStoreToProvider(String store) {
        if (store == null) return "GOOGLE_PLAY";
        return switch (store.toUpperCase()) {
            case "APP_STORE", "MAC_APP_STORE" -> "APPLE_APP_STORE";
            default -> "GOOGLE_PLAY";
        };
    }

    private Instant parseTimestampMs(Map<String, Object> event, String key) {
        Object val = event.get(key);
        if (val == null) return null;
        try {
            if (val instanceof Number n) {
                return Instant.ofEpochMilli(n.longValue());
            }
            if (val instanceof String s) {
                return Instant.ofEpochMilli(Long.parseLong(s));
            }
        } catch (Exception e) {
            log.debug("Failed to parse timestamp for key={}: {}", key, val);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Instant parseTimestamp(Map<String, Object> event, String key) {
        Object val = event.get(key);
        if (val == null) return null;
        try {
            if (val instanceof String s) {
                return Instant.parse(s);
            }
            if (val instanceof Number n) {
                return Instant.ofEpochMilli(n.longValue());
            }
        } catch (Exception e) {
            log.debug("Failed to parse timestamp for key={}: {}", key, val);
        }
        return null;
    }

    private void evictSubscriptionCache(UUID userId) {
        org.springframework.cache.Cache cache = cacheManager.getCache("subscriptionFeatures");
        if (cache != null) cache.evict(userId);
    }
}
