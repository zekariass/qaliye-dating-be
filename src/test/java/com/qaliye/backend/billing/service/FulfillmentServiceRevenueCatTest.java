package com.qaliye.backend.billing.service;

import com.qaliye.backend.billing.repository.BillingRepository;
import com.qaliye.backend.billing.repository.CreditLotRepository;
import com.qaliye.backend.billing.repository.PromotionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FulfillmentServiceRevenueCatTest {

    @Mock BillingRepository billingRepo;
    @Mock CreditLotRepository creditLotRepo;
    @Mock CreditService creditService;
    @Mock PromotionRepository promotionRepo;

    FulfillmentService fulfillmentService;

    UUID userId = UUID.randomUUID();
    UUID planId = UUID.randomUUID();
    UUID offerId = UUID.randomUUID();
    UUID subId = UUID.randomUUID();
    UUID ledgerEntryId = UUID.randomUUID();

    Instant periodStart = Instant.parse("2025-01-01T00:00:00Z");
    Instant periodEnd = Instant.parse("2025-02-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        fulfillmentService = new FulfillmentService(billingRepo, creditLotRepo, creditService, promotionRepo);
        lenient().when(creditLotRepo.getPlanBoostLimit(any())).thenReturn(1);
        lenient().doNothing().when(billingRepo).lockUserRowForUpdate(any());
        lenient().when(creditLotRepo.insertLedgerEntry(any(), any(), anyInt(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(ledgerEntryId);
    }

    // ── 1. Initial purchase: no active sub → insert new ──────────────────────

    @Test
    void fulfill_initialPurchase_noActiveSub_insertsNewSubscription() {
        when(billingRepo.lockAllSubscriptionsForUpdate(userId)).thenReturn(List.of());
        when(billingRepo.insertSubscription(eq(userId), eq(planId), eq("APPLE_APP_STORE"),
                eq("orig_123"), eq(offerId), eq("txn_123"), eq("ACTIVE"), eq(true),
                any(), any(), any()))
                .thenReturn(subId);
        when(billingRepo.findTransactionByProviderTxId("APPLE_APP_STORE", "txn_123"))
                .thenReturn(Optional.empty());
        when(billingRepo.findOfferById(offerId)).thenReturn(Optional.of(new BillingRepository.FullOfferRow(
                offerId, UUID.randomUUID(), null, "ET", "IOS",
                "USD", 0, true, "com.qaliye.premium", null, null,
                "PREMIUM", "MONTH", 1, planId,
                1, null, null, null, null)));

        UUID result = fulfillmentService.fulfillRevenueCatSubscription(
                userId, "orig_123", "txn_123", planId, offerId,
                periodStart, periodEnd, "APPLE_APP_STORE", true, "PURCHASE");

        verify(billingRepo).insertSubscription(eq(userId), eq(planId), eq("APPLE_APP_STORE"),
                eq("orig_123"), eq(offerId), eq("txn_123"), eq("ACTIVE"), eq(true),
                any(), any(), any());
        verify(billingRepo).insertTransaction(eq(userId), eq(subId), isNull(), eq(offerId), isNull(),
                eq("SUBSCRIPTION"), eq("PURCHASE"), eq(0), eq("USD"),
                eq("APPLE_APP_STORE"), eq("txn_123"), isNull(), isNull(), eq("COMPLETED"));
        verify(creditService).grantSubscriptionAllowance(eq(userId), eq(1L), eq(subId),
                eq("rc-credits-orig_123-2025-01-01T00:00:00Z"), eq(periodEnd));
        org.junit.jupiter.api.Assertions.assertEquals(subId, result);
    }

    // ── 2. Renewal: same stable ID found → update existing row ───────────────

    @Test
    void fulfill_renewal_sameStableId_updatesExistingSubscription() {
        BillingRepository.SubscriptionRow existing = new BillingRepository.SubscriptionRow(
                subId, "orig_123", "txn_old", planId, offerId, "APPLE_APP_STORE", "ACTIVE", true,
                periodStart, periodEnd);

        when(billingRepo.lockAllSubscriptionsForUpdate(userId))
                .thenReturn(List.of(existing));
        when(billingRepo.findTransactionByProviderTxId("APPLE_APP_STORE", "txn_new"))
                .thenReturn(Optional.empty());
        UUID result = fulfillmentService.fulfillRevenueCatSubscription(
                userId, "orig_123", "txn_new", planId, offerId,
                periodStart, periodEnd, "APPLE_APP_STORE", true, "RENEWAL");

        verify(billingRepo).updateSubscriptionById(eq(subId), eq(planId), eq("APPLE_APP_STORE"),
                eq("orig_123"), eq(offerId), eq("txn_new"), eq("ACTIVE"), eq(true),
                eq(periodStart), eq(periodEnd));
        verify(billingRepo, never()).insertSubscription(any(), any(), any(), any(), any(),
                any(), any(), anyBoolean(), any(), any(), any());
        org.junit.jupiter.api.Assertions.assertEquals(subId, result);
    }

    // ── 3. Duplicate transaction: idempotent, no second transaction inserted ─

    @Test
    void fulfill_duplicateTransaction_doesNotInsertSecondTransaction() {
        UUID existingTxId = UUID.randomUUID();
        BillingRepository.SubscriptionRow existing = new BillingRepository.SubscriptionRow(
                subId, "orig_123", "txn_123", planId, offerId, "APPLE_APP_STORE", "ACTIVE", true,
                periodStart, periodEnd);

        when(billingRepo.lockAllSubscriptionsForUpdate(userId))
                .thenReturn(List.of(existing));
        when(billingRepo.findTransactionByProviderTxId("APPLE_APP_STORE", "txn_123"))
                .thenReturn(Optional.of(existingTxId));
        fulfillmentService.fulfillRevenueCatSubscription(
                userId, "orig_123", "txn_123", planId, offerId,
                periodStart, periodEnd, "APPLE_APP_STORE", true, "PURCHASE");

        verify(billingRepo, never()).insertTransaction(any(), any(), any(), any(), any(),
                any(), any(), anyInt(), any(), any(), any(), any(), any(), any());
    }

    // ── 4. Restore: same stable ID, different transaction → updates same row ─

    @Test
    void fulfill_restore_sameStableIdDifferentTx_updatesSameRow() {
        BillingRepository.SubscriptionRow existing = new BillingRepository.SubscriptionRow(
                subId, "orig_123", "txn_original", planId, offerId, "APPLE_APP_STORE", "ACTIVE", true,
                periodStart, periodEnd);

        when(billingRepo.lockAllSubscriptionsForUpdate(userId))
                .thenReturn(List.of(existing));
        when(billingRepo.findTransactionByProviderTxId("APPLE_APP_STORE", "txn_restore"))
                .thenReturn(Optional.empty());
        UUID result = fulfillmentService.fulfillRevenueCatSubscription(
                userId, "orig_123", "txn_restore", planId, offerId,
                periodStart, periodEnd, "APPLE_APP_STORE", true, "PURCHASE");

        verify(billingRepo).updateSubscriptionById(eq(subId), eq(planId), eq("APPLE_APP_STORE"),
                eq("orig_123"), eq(offerId), eq("txn_restore"), eq("ACTIVE"), eq(true),
                eq(periodStart), eq(periodEnd));
        verify(billingRepo, never()).insertSubscription(any(), any(), any(), any(), any(),
                any(), any(), anyBoolean(), any(), any(), any());
        org.junit.jupiter.api.Assertions.assertEquals(subId, result);
    }

    // ── 5. Upgrade/replacement: different stable ID → expire old, insert new ─

    @Test
    void fulfill_upgrade_differentStableId_replacesOldAndInsertsNew() {
        UUID newSubId = UUID.randomUUID();
        UUID newPlanId = UUID.randomUUID();
        UUID newOfferId = UUID.randomUUID();

        BillingRepository.SubscriptionRow oldActive = new BillingRepository.SubscriptionRow(
                subId, "orig_old", "txn_old", planId, offerId, "APPLE_APP_STORE", "ACTIVE", true,
                periodStart, periodEnd);

        when(billingRepo.lockAllSubscriptionsForUpdate(userId))
                .thenReturn(List.of(oldActive));
        when(billingRepo.insertSubscription(eq(userId), eq(newPlanId), eq("APPLE_APP_STORE"),
                eq("orig_new"), eq(newOfferId), eq("txn_new"), eq("ACTIVE"), eq(true),
                any(), any(), any()))
                .thenReturn(newSubId);
        when(billingRepo.findTransactionByProviderTxId("APPLE_APP_STORE", "txn_new"))
                .thenReturn(Optional.empty());
        UUID result = fulfillmentService.fulfillRevenueCatSubscription(
                userId, "orig_new", "txn_new", newPlanId, newOfferId,
                periodStart, periodEnd, "APPLE_APP_STORE", true, "UPGRADE");

        verify(billingRepo).markSubscriptionReplaced(subId);
        verify(billingRepo).insertSubscription(eq(userId), eq(newPlanId), eq("APPLE_APP_STORE"),
                eq("orig_new"), eq(newOfferId), eq("txn_new"), eq("ACTIVE"), eq(true),
                any(), any(), any());
        org.junit.jupiter.api.Assertions.assertEquals(newSubId, result);
    }

    // ── 6. Active sub with same stable ID but mismatched provider → repair ───

    @Test
    void fulfill_activeSubSameStableIdButDifferentProvider_repairsAndUpdates() {
        BillingRepository.SubscriptionRow active = new BillingRepository.SubscriptionRow(
                subId, "orig_123", "txn_old", planId, offerId, "GOOGLE_PLAY", "ACTIVE", true,
                periodStart, periodEnd);

        when(billingRepo.lockAllSubscriptionsForUpdate(userId))
                .thenReturn(List.of(active));
        when(billingRepo.findTransactionByProviderTxId("APPLE_APP_STORE", "txn_new"))
                .thenReturn(Optional.empty());
        UUID result = fulfillmentService.fulfillRevenueCatSubscription(
                userId, "orig_123", "txn_new", planId, offerId,
                periodStart, periodEnd, "APPLE_APP_STORE", true, "PURCHASE");

        // Since stableSubId matches but provider doesn't, this is treated as replacement
        // (active.provider = GOOGLE_PLAY != APPLE_APP_STORE)
        verify(billingRepo).markSubscriptionReplaced(subId);
        verify(billingRepo).insertSubscription(any(), any(), any(), any(), any(), any(),
                any(), anyBoolean(), any(), any(), any());
    }

    // ── 7. Reactivate old expired sub by stableSubId while different active sub exists ─

    @Test
    void fulfill_reactivateOldSubByStableId_replacesActiveAndUpdatesOld() {
        UUID oldSubId = UUID.randomUUID();
        UUID activeSubId = UUID.randomUUID();

        BillingRepository.SubscriptionRow oldExpired = new BillingRepository.SubscriptionRow(
                oldSubId, "orig_123", "txn_old", planId, offerId, "APPLE_APP_STORE", "EXPIRED", false,
                periodStart, periodEnd);
        BillingRepository.SubscriptionRow currentActive = new BillingRepository.SubscriptionRow(
                activeSubId, "orig_456", "txn_active", planId, offerId, "APPLE_APP_STORE", "ACTIVE", true,
                periodStart, periodEnd);

        when(billingRepo.lockAllSubscriptionsForUpdate(userId))
                .thenReturn(List.of(oldExpired, currentActive));
        when(billingRepo.findTransactionByProviderTxId("APPLE_APP_STORE", "txn_new"))
                .thenReturn(Optional.empty());
        UUID result = fulfillmentService.fulfillRevenueCatSubscription(
                userId, "orig_123", "txn_new", planId, offerId,
                periodStart, periodEnd, "APPLE_APP_STORE", true, "PURCHASE");

        verify(billingRepo).markSubscriptionReplaced(activeSubId);
        verify(billingRepo).updateSubscriptionById(eq(oldSubId), eq(planId), eq("APPLE_APP_STORE"),
                eq("orig_123"), eq(offerId), eq("txn_new"), eq("ACTIVE"), eq(true),
                eq(periodStart), eq(periodEnd));
        verify(billingRepo, never()).insertSubscription(any(), any(), any(), any(), any(),
                any(), any(), anyBoolean(), any(), any(), any());
        org.junit.jupiter.api.Assertions.assertEquals(oldSubId, result);
    }

    // ── 8. Null stableSubId throws IllegalArgumentException ──────────────────

    @Test
    void fulfill_nullStableSubId_throwsException() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                fulfillmentService.fulfillRevenueCatSubscription(
                        userId, null, "txn_123", planId, offerId,
                        periodStart, periodEnd, "APPLE_APP_STORE", true, "PURCHASE"));
    }

    // ── 9. Boost idempotency: same stableSubId + period day → no duplicate ───

    @Test
    void fulfill_boostIdempotencyKey_usesStableSubIdAndPeriodDay() {
        BillingRepository.SubscriptionRow existing = new BillingRepository.SubscriptionRow(
                subId, "orig_123", "txn_123", planId, offerId, "APPLE_APP_STORE", "ACTIVE", true,
                periodStart, periodEnd);

        when(billingRepo.lockAllSubscriptionsForUpdate(userId))
                .thenReturn(List.of(existing));
        when(billingRepo.findTransactionByProviderTxId("APPLE_APP_STORE", "txn_123"))
                .thenReturn(Optional.of(UUID.randomUUID()));
        when(billingRepo.findOfferById(offerId)).thenReturn(Optional.of(new BillingRepository.FullOfferRow(
                offerId, UUID.randomUUID(), null, "ET", "IOS",
                "USD", 0, true, "com.qaliye.premium", null, null,
                "PREMIUM", "MONTH", 1, planId,
                1, null, null, null, null)));

        fulfillmentService.fulfillRevenueCatSubscription(
                userId, "orig_123", "txn_123", planId, offerId,
                periodStart, periodEnd, "APPLE_APP_STORE", true, "PURCHASE");

        verify(creditService).grantSubscriptionAllowance(eq(userId), eq(1L), eq(subId),
                eq("rc-credits-orig_123-2025-01-01T00:00:00Z"), eq(periodEnd));
    }
}
