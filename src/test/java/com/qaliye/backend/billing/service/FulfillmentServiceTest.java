package com.qaliye.backend.billing.service;

import com.qaliye.backend.billing.repository.BillingRepository;
import com.qaliye.backend.billing.repository.CreditLotRepository;
import com.qaliye.backend.billing.repository.PromotionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FulfillmentServiceTest {

    @Mock BillingRepository billingRepo;
    @Mock CreditLotRepository creditLotRepo;
    @Mock PromotionRepository promotionRepo;

    FulfillmentService fulfillmentService;

    UUID userId = UUID.randomUUID();
    UUID planId = UUID.randomUUID();
    UUID offerId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID methodId = UUID.randomUUID();
    UUID subId = UUID.randomUUID();
    UUID ledgerEntryId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        fulfillmentService = new FulfillmentService(billingRepo, creditLotRepo, promotionRepo);
        lenient().when(creditLotRepo.getPlanBoostLimit(any())).thenReturn(1);
        lenient().when(creditLotRepo.insertLedgerEntry(any(), any(), anyInt(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(ledgerEntryId);
    }

    private BillingRepository.OrderRow buildOrder(String methodCode) {
        return new BillingRepository.OrderRow(
                orderId, userId, offerId, methodId,
                "QAL-test1234", "VERIFIED", null,
                14900, "ETB",
                "ONLINE_PAYMENT", null, methodCode, null,
                null, null,
                null, Instant.now(), null,
                null, null,
                null, 0
        );
    }

    private BillingRepository.FullOfferRow buildOffer() {
        return new BillingRepository.FullOfferRow(
                offerId, UUID.randomUUID(), null,
                "ET", "ANDROID",
                "ETB", 14900, true,
                null,
                "PREMIUM_MONTHLY", "MONTH", 1, planId,
                null, null, null, null
        );
    }

    // ── 1. No active subscription → no extension, normal periodEnd ───────────

    @Test
    void fulfillSubscription_noActiveSub_normalPeriodEnd() {
        when(billingRepo.findOrderById(orderId)).thenReturn(Optional.of(buildOrder("chapa")));
        when(billingRepo.findOfferById(offerId)).thenReturn(Optional.of(buildOffer()));
        when(billingRepo.findActiveSubscription(userId)).thenReturn(Optional.empty());
        when(billingRepo.upsertSubscription(eq(userId), eq(planId), eq("CHAPA"),
                any(), eq(offerId), isNull(), eq("ACTIVE"), eq(true),
                any(), any(), any())).thenReturn(subId);

        fulfillmentService.fulfillVerifiedOrder(orderId, userId);

        ArgumentCaptor<Instant> periodEndCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(billingRepo).upsertSubscription(eq(userId), eq(planId), eq("CHAPA"),
                any(), eq(offerId), isNull(), eq("ACTIVE"), eq(true),
                any(), any(), periodEndCaptor.capture());

        // Monthly = 30 days from now; allow a 2-second tolerance for test execution time
        Instant expectedEnd = Instant.now().plus(30, ChronoUnit.DAYS);
        long diffSeconds = Math.abs(periodEndCaptor.getValue().getEpochSecond() - expectedEnd.getEpochSecond());
        assertTrue(diffSeconds < 5, "periodEnd should be ~30 days from now, diff=" + diffSeconds + "s");
    }

    // ── 2. Active PROMOTION subscription → periodEnd extended by remaining days ─

    @Test
    void fulfillSubscription_activePromotionSub_extendsPeriodEnd() {
        Instant now = Instant.now();
        Instant promoEnd = now.plus(10, ChronoUnit.DAYS); // 10 days remaining on promotion

        BillingRepository.ActiveSubRow promoSub = new BillingRepository.ActiveSubRow(
                subId, planId, "ACTIVE", false,
                now.minus(20, ChronoUnit.DAYS), promoEnd,
                "PROMOTION", "FREE_PREMIUM", "{}",
                "MONTH", 1
        );

        when(billingRepo.findOrderById(orderId)).thenReturn(Optional.of(buildOrder("chapa")));
        when(billingRepo.findOfferById(offerId)).thenReturn(Optional.of(buildOffer()));
        when(billingRepo.findActiveSubscription(userId)).thenReturn(Optional.of(promoSub));
        when(billingRepo.upsertSubscription(eq(userId), eq(planId), eq("CHAPA"),
                any(), eq(offerId), isNull(), eq("ACTIVE"), eq(true),
                any(), any(), any())).thenReturn(subId);

        fulfillmentService.fulfillVerifiedOrder(orderId, userId);

        ArgumentCaptor<Instant> periodEndCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(billingRepo).upsertSubscription(eq(userId), eq(planId), eq("CHAPA"),
                any(), eq(offerId), isNull(), eq("ACTIVE"), eq(true),
                any(), any(), periodEndCaptor.capture());

        // Expected: 30 days (monthly) + 10 days (remaining promo) = 40 days
        Instant expectedEnd = now.plus(40, ChronoUnit.DAYS);
        long diffSeconds = Math.abs(periodEndCaptor.getValue().getEpochSecond() - expectedEnd.getEpochSecond());
        assertTrue(diffSeconds < 5, "periodEnd should be ~40 days from now (30 paid + 10 promo), diff=" + diffSeconds + "s");
    }

    // ── 3. Active PROMOTION subscription with expired period → no extension ───

    @Test
    void fulfillSubscription_promotionSubExpired_noExtension() {
        Instant now = Instant.now();
        Instant promoEnd = now.minus(1, ChronoUnit.DAYS); // promotion already expired

        BillingRepository.ActiveSubRow expiredPromoSub = new BillingRepository.ActiveSubRow(
                subId, planId, "ACTIVE", false,
                now.minus(31, ChronoUnit.DAYS), promoEnd,
                "PROMOTION", "FREE_PREMIUM", "{}",
                "MONTH", 1
        );

        when(billingRepo.findOrderById(orderId)).thenReturn(Optional.of(buildOrder("chapa")));
        when(billingRepo.findOfferById(offerId)).thenReturn(Optional.of(buildOffer()));
        when(billingRepo.findActiveSubscription(userId)).thenReturn(Optional.of(expiredPromoSub));
        when(billingRepo.upsertSubscription(eq(userId), eq(planId), eq("CHAPA"),
                any(), eq(offerId), isNull(), eq("ACTIVE"), eq(true),
                any(), any(), any())).thenReturn(subId);

        fulfillmentService.fulfillVerifiedOrder(orderId, userId);

        ArgumentCaptor<Instant> periodEndCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(billingRepo).upsertSubscription(eq(userId), eq(planId), eq("CHAPA"),
                any(), eq(offerId), isNull(), eq("ACTIVE"), eq(true),
                any(), any(), periodEndCaptor.capture());

        // No extension since promo already expired
        Instant expectedEnd = now.plus(30, ChronoUnit.DAYS);
        long diffSeconds = Math.abs(periodEndCaptor.getValue().getEpochSecond() - expectedEnd.getEpochSecond());
        assertTrue(diffSeconds < 5, "periodEnd should be ~30 days (no extension), diff=" + diffSeconds + "s");
    }

    // ── 4. Active non-PROMOTION subscription (e.g. CHAPA) → no extension ──────

    @Test
    void fulfillSubscription_activeNonPromotionSub_noExtension() {
        Instant now = Instant.now();
        Instant existingEnd = now.plus(15, ChronoUnit.DAYS);

        BillingRepository.ActiveSubRow existingPaidSub = new BillingRepository.ActiveSubRow(
                subId, planId, "ACTIVE", true,
                now.minus(15, ChronoUnit.DAYS), existingEnd,
                "CHAPA", "PREMIUM", "{}",
                "MONTH", 1
        );

        when(billingRepo.findOrderById(orderId)).thenReturn(Optional.of(buildOrder("chapa")));
        when(billingRepo.findOfferById(offerId)).thenReturn(Optional.of(buildOffer()));
        when(billingRepo.findActiveSubscription(userId)).thenReturn(Optional.of(existingPaidSub));
        when(billingRepo.upsertSubscription(eq(userId), eq(planId), eq("CHAPA"),
                any(), eq(offerId), isNull(), eq("ACTIVE"), eq(true),
                any(), any(), any())).thenReturn(subId);

        fulfillmentService.fulfillVerifiedOrder(orderId, userId);

        ArgumentCaptor<Instant> periodEndCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(billingRepo).upsertSubscription(eq(userId), eq(planId), eq("CHAPA"),
                any(), eq(offerId), isNull(), eq("ACTIVE"), eq(true),
                any(), any(), periodEndCaptor.capture());

        // No extension for non-PROMOTION subs; just 30 days from now
        Instant expectedEnd = now.plus(30, ChronoUnit.DAYS);
        long diffSeconds = Math.abs(periodEndCaptor.getValue().getEpochSecond() - expectedEnd.getEpochSecond());
        assertTrue(diffSeconds < 5, "periodEnd should be ~30 days (no extension for non-promo), diff=" + diffSeconds + "s");
    }

    // ── 5. Manual transfer (bank) path also gets extension ────────────────────

    @Test
    void fulfillSubscription_manualTransferWithPromotion_extendsPeriodEnd() {
        Instant now = Instant.now();
        Instant promoEnd = now.plus(5, ChronoUnit.DAYS);

        BillingRepository.ActiveSubRow promoSub = new BillingRepository.ActiveSubRow(
                subId, planId, "ACTIVE", false,
                now.minus(25, ChronoUnit.DAYS), promoEnd,
                "PROMOTION", "FREE_PREMIUM", "{}",
                "MONTH", 1
        );

        when(billingRepo.findOrderById(orderId)).thenReturn(Optional.of(buildOrder("cbe")));
        when(billingRepo.findOfferById(offerId)).thenReturn(Optional.of(buildOffer()));
        when(billingRepo.findActiveSubscription(userId)).thenReturn(Optional.of(promoSub));
        when(billingRepo.upsertSubscription(eq(userId), eq(planId), eq("BANK_TRANSFER"),
                any(), eq(offerId), isNull(), eq("ACTIVE"), eq(true),
                any(), any(), any())).thenReturn(subId);

        fulfillmentService.fulfillVerifiedOrder(orderId, userId);

        ArgumentCaptor<Instant> periodEndCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(billingRepo).upsertSubscription(eq(userId), eq(planId), eq("BANK_TRANSFER"),
                any(), eq(offerId), isNull(), eq("ACTIVE"), eq(true),
                any(), any(), periodEndCaptor.capture());

        // 30 days (monthly) + 5 days (remaining promo) = 35 days
        Instant expectedEnd = now.plus(35, ChronoUnit.DAYS);
        long diffSeconds = Math.abs(periodEndCaptor.getValue().getEpochSecond() - expectedEnd.getEpochSecond());
        assertTrue(diffSeconds < 5, "periodEnd should be ~35 days (30 paid + 5 promo), diff=" + diffSeconds + "s");
    }
}
