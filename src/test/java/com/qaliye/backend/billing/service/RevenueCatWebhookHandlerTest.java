package com.qaliye.backend.billing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaliye.backend.billing.repository.BillingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RevenueCatWebhookHandlerTest {

    @Mock BillingRepository billingRepo;
    @Mock FulfillmentService fulfillmentService;
    @Mock CacheManager cacheManager;
    @Mock Cache subscriptionCache;
    ObjectMapper objectMapper = new ObjectMapper();

    RevenueCatWebhookHandler handler;

    UUID userId = UUID.randomUUID();
    UUID eventDbId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(cacheManager.getCache("subscriptionFeatures")).thenReturn(subscriptionCache);
        handler = new RevenueCatWebhookHandler(billingRepo, fulfillmentService, objectMapper, cacheManager);
    }

    // ── 1. Initial purchase creates one active subscription ──────────────────

    @Test
    void handle_initialPurchase_activatesSubscription() throws Exception {
        UUID offerId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID subId = UUID.randomUUID();
        UUID txDbId = UUID.randomUUID();
        String payload = """
                {
                    "event": {
                        "id": "evt_001",
                        "type": "INITIAL_PURCHASE",
                        "app_user_id": "%s",
                        "product_id": "qaliye_premium_monthly",
                        "transaction_id": "txn_123",
                        "original_transaction_id": "orig_123",
                        "store": "APP_STORE",
                        "price": 4.99,
                        "currency": "USD",
                        "purchased_at_ms": 1735689600000,
                        "expiration_at_ms": 1735776000000
                    }
                }
                """.formatted(userId);

        when(billingRepo.findEventStatus("REVENUECAT", "evt_001")).thenReturn(Optional.empty());
        when(billingRepo.logEvent(eq("REVENUECAT"), eq("evt_001"), eq("INITIAL_PURCHASE"), any(), any(),
                eq(userId), eq(499), eq("USD"), any()))
                .thenReturn(Optional.of(eventDbId));
        when(billingRepo.findOfferByExternalProductId("qaliye_premium_monthly"))
                .thenReturn(Optional.of(createSubscriptionOffer(offerId, planId)));
        when(fulfillmentService.fulfillRevenueCatSubscription(any(), any(), any(), any(), any(),
                any(), any(), any(), anyBoolean(), any()))
                .thenReturn(subId);
        when(billingRepo.findTransactionByProviderTxId("APPLE_APP_STORE", "txn_123"))
                .thenReturn(Optional.of(txDbId));

        handler.handle(payload.getBytes(StandardCharsets.UTF_8));

        verify(fulfillmentService).fulfillRevenueCatSubscription(
                eq(userId), eq("orig_123"), eq("txn_123"), eq(planId), eq(offerId),
                any(), any(), eq("APPLE_APP_STORE"), eq(true), eq("PURCHASE"));
        verify(billingRepo).updateEventLinks(eventDbId, subId, txDbId);
        verify(billingRepo).updateEventStatus(eventDbId, "PROCESSED", null);
    }

    // ── 2. Renewal updates the same subscription row (stable ID) ─────────────

    @Test
    void handle_renewal_usesStableOriginalTransactionId() throws Exception {
        UUID offerId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID subId = UUID.randomUUID();
        String payload = """
                {
                    "event": {
                        "id": "evt_002",
                        "type": "RENEWAL",
                        "app_user_id": "%s",
                        "product_id": "qaliye_premium_monthly",
                        "transaction_id": "txn_456",
                        "original_transaction_id": "orig_123",
                        "store": "APP_STORE",
                        "price": 4.99,
                        "currency": "USD",
                        "purchased_at_ms": 1735689600000,
                        "expiration_at_ms": 1735776000000
                    }
                }
                """.formatted(userId);

        when(billingRepo.findEventStatus("REVENUECAT", "evt_002")).thenReturn(Optional.empty());
        when(billingRepo.logEvent(eq("REVENUECAT"), eq("evt_002"), eq("RENEWAL"), any(), any(),
                eq(userId), eq(499), eq("USD"), any()))
                .thenReturn(Optional.of(eventDbId));
        when(billingRepo.findOfferByExternalProductId("qaliye_premium_monthly"))
                .thenReturn(Optional.of(createSubscriptionOffer(offerId, planId)));
        when(fulfillmentService.fulfillRevenueCatSubscription(any(), any(), any(), any(), any(),
                any(), any(), any(), anyBoolean(), any()))
                .thenReturn(subId);
        when(billingRepo.findTransactionByProviderTxId("APPLE_APP_STORE", "txn_456"))
                .thenReturn(Optional.empty());

        handler.handle(payload.getBytes(StandardCharsets.UTF_8));

        // stableSubId = orig_123 (not txn_456), providerSubRef = txn_456
        verify(fulfillmentService).fulfillRevenueCatSubscription(
                eq(userId), eq("orig_123"), eq("txn_456"), eq(planId), eq(offerId),
                any(), any(), eq("APPLE_APP_STORE"), eq(true), eq("RENEWAL"));
        verify(billingRepo).updateEventLinks(eventDbId, subId, null);
    }

    // ── 3. Duplicate webhook delivery does not create another subscription ──

    @Test
    void handle_duplicateEvent_alreadyProcessed_skipsFulfillment() throws Exception {
        String payload = """
                {
                    "event": {
                        "id": "evt_001",
                        "type": "INITIAL_PURCHASE",
                        "app_user_id": "%s",
                        "product_id": "qaliye_premium_monthly",
                        "transaction_id": "txn_123",
                        "original_transaction_id": "orig_123",
                        "store": "APP_STORE",
                        "price": 4.99,
                        "currency": "USD"
                    }
                }
                """.formatted(userId);

        when(billingRepo.findEventStatus("REVENUECAT", "evt_001"))
                .thenReturn(Optional.of(new BillingRepository.EventStatusRow(eventDbId, "PROCESSED")));

        handler.handle(payload.getBytes(StandardCharsets.UTF_8));

        verify(fulfillmentService, never()).fulfillRevenueCatSubscription(
                any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());
        verify(billingRepo, never()).logEvent(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ── 4. Restore updates the same subscription safely ──────────────────────

    @Test
    void handle_restore_sameStableId_fulfillsSubscription() throws Exception {
        UUID offerId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID subId = UUID.randomUUID();
        // Restore sends INITIAL_PURCHASE with same original_transaction_id but new transaction_id
        String payload = """
                {
                    "event": {
                        "id": "evt_restore",
                        "type": "INITIAL_PURCHASE",
                        "app_user_id": "%s",
                        "product_id": "qaliye_premium_monthly",
                        "transaction_id": "txn_restore_789",
                        "original_transaction_id": "orig_123",
                        "store": "APP_STORE",
                        "price": 4.99,
                        "currency": "USD"
                    }
                }
                """.formatted(userId);

        when(billingRepo.findEventStatus("REVENUECAT", "evt_restore")).thenReturn(Optional.empty());
        when(billingRepo.logEvent(eq("REVENUECAT"), eq("evt_restore"), eq("INITIAL_PURCHASE"), any(), any(),
                eq(userId), eq(499), eq("USD"), any()))
                .thenReturn(Optional.of(eventDbId));
        when(billingRepo.findOfferByExternalProductId("qaliye_premium_monthly"))
                .thenReturn(Optional.of(createSubscriptionOffer(offerId, planId)));
        when(fulfillmentService.fulfillRevenueCatSubscription(any(), any(), any(), any(), any(),
                any(), any(), any(), anyBoolean(), any()))
                .thenReturn(subId);
        when(billingRepo.findTransactionByProviderTxId("APPLE_APP_STORE", "txn_restore_789"))
                .thenReturn(Optional.empty());

        handler.handle(payload.getBytes(StandardCharsets.UTF_8));

        // Uses stable original_transaction_id, not the new restore transaction_id
        verify(fulfillmentService).fulfillRevenueCatSubscription(
                eq(userId), eq("orig_123"), eq("txn_restore_789"), eq(planId), eq(offerId),
                any(), any(), eq("APPLE_APP_STORE"), eq(true), eq("PURCHASE"));
    }

    // ── 5. Upgrade/replacement (PRODUCT_CHANGE) ──────────────────────────────

    @Test
    void handle_productChange_passesUpgradeTransactionType() throws Exception {
        UUID offerId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID subId = UUID.randomUUID();
        String payload = """
                {
                    "event": {
                        "id": "evt_upgrade",
                        "type": "PRODUCT_CHANGE",
                        "app_user_id": "%s",
                        "product_id": "qaliye_premium_3month",
                        "transaction_id": "txn_upgrade_999",
                        "original_transaction_id": "orig_123",
                        "store": "APP_STORE",
                        "price": 9.99,
                        "currency": "USD"
                    }
                }
                """.formatted(userId);

        when(billingRepo.findEventStatus("REVENUECAT", "evt_upgrade")).thenReturn(Optional.empty());
        when(billingRepo.logEvent(eq("REVENUECAT"), eq("evt_upgrade"), eq("PRODUCT_CHANGE"), any(), any(),
                eq(userId), eq(999), eq("USD"), any()))
                .thenReturn(Optional.of(eventDbId));
        when(billingRepo.findOfferByExternalProductId("qaliye_premium_3month"))
                .thenReturn(Optional.of(createSubscriptionOffer(offerId, planId)));
        when(fulfillmentService.fulfillRevenueCatSubscription(any(), any(), any(), any(), any(),
                any(), any(), any(), anyBoolean(), any()))
                .thenReturn(subId);
        when(billingRepo.findTransactionByProviderTxId("APPLE_APP_STORE", "txn_upgrade_999"))
                .thenReturn(Optional.empty());

        handler.handle(payload.getBytes(StandardCharsets.UTF_8));

        verify(fulfillmentService).fulfillRevenueCatSubscription(
                eq(userId), eq("orig_123"), eq("txn_upgrade_999"), eq(planId), eq(offerId),
                any(), any(), eq("APPLE_APP_STORE"), eq(true), eq("UPGRADE"));
    }

    // ── 6. Previously failed event is retried ────────────────────────────────

    @Test
    void handle_failedEvent_retriesProcessing() throws Exception {
        UUID offerId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID subId = UUID.randomUUID();
        String payload = """
                {
                    "event": {
                        "id": "evt_001",
                        "type": "INITIAL_PURCHASE",
                        "app_user_id": "%s",
                        "product_id": "qaliye_premium_monthly",
                        "transaction_id": "txn_123",
                        "original_transaction_id": "orig_123",
                        "store": "APP_STORE",
                        "price": 4.99,
                        "currency": "USD"
                    }
                }
                """.formatted(userId);

        when(billingRepo.findEventStatus("REVENUECAT", "evt_001"))
                .thenReturn(Optional.of(new BillingRepository.EventStatusRow(eventDbId, "FAILED")));
        when(billingRepo.findOfferByExternalProductId("qaliye_premium_monthly"))
                .thenReturn(Optional.of(createSubscriptionOffer(offerId, planId)));
        when(fulfillmentService.fulfillRevenueCatSubscription(any(), any(), any(), any(), any(),
                any(), any(), any(), anyBoolean(), any()))
                .thenReturn(subId);
        when(billingRepo.findTransactionByProviderTxId("APPLE_APP_STORE", "txn_123"))
                .thenReturn(Optional.empty());

        handler.handle(payload.getBytes(StandardCharsets.UTF_8));

        verify(billingRepo).updateEventStatus(eventDbId, "PROCESSING", null);
        verify(fulfillmentService).fulfillRevenueCatSubscription(
                eq(userId), eq("orig_123"), eq("txn_123"), eq(planId), eq(offerId),
                any(), any(), eq("APPLE_APP_STORE"), eq(true), eq("PURCHASE"));
        verify(billingRepo).updateEventStatus(eventDbId, "PROCESSED", null);
    }

    // ── 7. Cancellation and expiration use stable ID ────────────────────────

    @Test
    void handle_cancellation_cancelsSubscriptionByStableId() throws Exception {
        String payload = """
                {
                    "event": {
                        "id": "evt_cancel",
                        "type": "CANCELLATION",
                        "app_user_id": "%s",
                        "original_transaction_id": "orig_123"
                    }
                }
                """.formatted(userId);

        when(billingRepo.findEventStatus("REVENUECAT", "evt_cancel")).thenReturn(Optional.empty());
        when(billingRepo.logEvent(eq("REVENUECAT"), eq("evt_cancel"), eq("CANCELLATION"), any(), any(),
                eq(userId), any(), any(), any()))
                .thenReturn(Optional.of(eventDbId));

        handler.handle(payload.getBytes(StandardCharsets.UTF_8));

        verify(billingRepo).cancelSubscription("orig_123");
        verify(billingRepo).updateEventStatus(eventDbId, "PROCESSED", null);
    }

    @Test
    void handle_expiration_expiresSubscriptionByStableId() throws Exception {
        String payload = """
                {
                    "event": {
                        "id": "evt_expire",
                        "type": "EXPIRATION",
                        "app_user_id": "%s",
                        "original_transaction_id": "orig_456"
                    }
                }
                """.formatted(userId);

        when(billingRepo.findEventStatus("REVENUECAT", "evt_expire")).thenReturn(Optional.empty());
        when(billingRepo.logEvent(eq("REVENUECAT"), eq("evt_expire"), eq("EXPIRATION"), any(), any(),
                eq(userId), any(), any(), any()))
                .thenReturn(Optional.of(eventDbId));

        handler.handle(payload.getBytes(StandardCharsets.UTF_8));

        verify(billingRepo).updateSubscriptionStatus("orig_456", "EXPIRED");
        verify(billingRepo).updateEventStatus(eventDbId, "PROCESSED", null);
    }

    // ── Edge cases ───────────────────────────────────────────────────────────

    @Test
    void handle_noEventObject_returnsWithoutError() {
        String payload = """
                { "not_event": {} }
                """;

        handler.handle(payload.getBytes(StandardCharsets.UTF_8));

        verify(billingRepo, never()).logEvent(any(), any(), any(), any(), any(),
                any(), any(), any(), any());
        verify(fulfillmentService, never()).fulfillRevenueCatSubscription(
                any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void handle_noAppUserId_returnsWithoutError() {
        String payload = """
                {
                    "event": {
                        "type": "INITIAL_PURCHASE",
                        "product_id": "qaliye_premium_monthly"
                    }
                }
                """;

        handler.handle(payload.getBytes(StandardCharsets.UTF_8));

        verify(billingRepo, never()).logEvent(any(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    void handle_invalidAppUserId_returnsWithoutError() {
        String payload = """
                {
                    "event": {
                        "type": "INITIAL_PURCHASE",
                        "app_user_id": "not-a-uuid",
                        "product_id": "qaliye_premium_monthly"
                    }
                }
                """;

        handler.handle(payload.getBytes(StandardCharsets.UTF_8));

        verify(billingRepo, never()).logEvent(any(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    void handle_noMatchingOffer_doesNotFulfill() throws Exception {
        String payload = """
                {
                    "event": {
                        "id": "evt_001",
                        "type": "INITIAL_PURCHASE",
                        "app_user_id": "%s",
                        "product_id": "unknown_product",
                        "transaction_id": "txn_123",
                        "original_transaction_id": "orig_123",
                        "store": "APP_STORE",
                        "price": 4.99,
                        "currency": "USD"
                    }
                }
                """.formatted(userId);

        when(billingRepo.findEventStatus("REVENUECAT", "evt_001")).thenReturn(Optional.empty());
        when(billingRepo.logEvent(eq("REVENUECAT"), eq("evt_001"), eq("INITIAL_PURCHASE"), any(), any(),
                eq(userId), eq(499), eq("USD"), any()))
                .thenReturn(Optional.of(eventDbId));
        when(billingRepo.findOfferByExternalProductId("unknown_product"))
                .thenReturn(Optional.empty());

        handler.handle(payload.getBytes(StandardCharsets.UTF_8));

        verify(fulfillmentService, never()).fulfillRevenueCatSubscription(
                any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());
        // Event is still marked as processed (no offer match is not an error)
        verify(billingRepo).updateEventStatus(eventDbId, "PROCESSED", null);
    }

    @Test
    void handle_billingIssue_updatesStatusToPastDue() throws Exception {
        String payload = """
                {
                    "event": {
                        "id": "evt_billing",
                        "type": "BILLING_ISSUE",
                        "app_user_id": "%s",
                        "original_transaction_id": "orig_123"
                    }
                }
                """.formatted(userId);

        when(billingRepo.findEventStatus("REVENUECAT", "evt_billing")).thenReturn(Optional.empty());
        when(billingRepo.logEvent(eq("REVENUECAT"), eq("evt_billing"), eq("BILLING_ISSUE"), any(), any(),
                eq(userId), any(), any(), any()))
                .thenReturn(Optional.of(eventDbId));

        handler.handle(payload.getBytes(StandardCharsets.UTF_8));

        verify(billingRepo).updateSubscriptionStatus("orig_123", "PAST_DUE");
        verify(billingRepo).updateEventStatus(eventDbId, "PROCESSED", null);
    }

    @Test
    void handle_eventIdFieldUsedWhenPresent() throws Exception {
        UUID offerId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID subId = UUID.randomUUID();
        String payload = """
                {
                    "event": {
                        "id": "B9B077F8-2870-4203-814B-0EAD897C6D89",
                        "type": "INITIAL_PURCHASE",
                        "app_user_id": "%s",
                        "product_id": "qaliye_premium_monthly",
                        "transaction_id": "txn_123",
                        "original_transaction_id": "orig_123",
                        "store": "TEST_STORE",
                        "price": 4.99,
                        "currency": "USD"
                    }
                }
                """.formatted(userId);

        when(billingRepo.findEventStatus("REVENUECAT", "B9B077F8-2870-4203-814B-0EAD897C6D89")).thenReturn(Optional.empty());
        when(billingRepo.logEvent(eq("REVENUECAT"), eq("B9B077F8-2870-4203-814B-0EAD897C6D89"), eq("INITIAL_PURCHASE"), any(), any(),
                eq(userId), eq(499), eq("USD"), any()))
                .thenReturn(Optional.of(eventDbId));
        when(billingRepo.findOfferByExternalProductId("qaliye_premium_monthly"))
                .thenReturn(Optional.of(createSubscriptionOffer(offerId, planId)));
        when(fulfillmentService.fulfillRevenueCatSubscription(any(), any(), any(), any(), any(),
                any(), any(), any(), anyBoolean(), any()))
                .thenReturn(subId);
        when(billingRepo.findTransactionByProviderTxId("GOOGLE_PLAY", "txn_123"))
                .thenReturn(Optional.empty());

        handler.handle(payload.getBytes(StandardCharsets.UTF_8));

        verify(billingRepo).findEventStatus("REVENUECAT", "B9B077F8-2870-4203-814B-0EAD897C6D89");
        // TEST_STORE maps to GOOGLE_PLAY (default)
        verify(fulfillmentService).fulfillRevenueCatSubscription(
                eq(userId), eq("orig_123"), eq("txn_123"), eq(planId), eq(offerId),
                any(), any(), eq("GOOGLE_PLAY"), eq(true), eq("PURCHASE"));
    }

    private BillingRepository.FullOfferRow createSubscriptionOffer(UUID offerId, UUID planId) {
        return new BillingRepository.FullOfferRow(
                offerId, UUID.randomUUID(), null,
                "GLOBAL", "IOS",
                "USD", 799, true,
                "qaliye_premium_monthly",
                "PREMIUM_MONTHLY", "MONTH", 1, planId,
                null, null, null, null
        );
    }
}
