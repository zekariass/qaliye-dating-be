package com.qaliye.backend.billing.service;

import com.qaliye.backend.billing.repository.BillingRepository;
import com.qaliye.backend.billing.repository.PromotionRepository;
import com.qaliye.backend.billing.repository.PromotionRepository.CampaignRow;
import com.qaliye.backend.billing.repository.PromotionRepository.RedemptionRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PromotionFulfillmentServiceTest {

    @Mock PromotionRepository promotionRepo;
    @Mock BillingRepository billingRepo;
    @Mock CreditService creditService;

    PromotionFulfillmentService service;

    @Test
    void fulfillPurchasePromotion_withIncludedCredits_grantsCredits() {
        service = new PromotionFulfillmentService(promotionRepo, billingRepo, creditService);

        UUID orderId = UUID.randomUUID();
        UUID subId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        UUID redemptionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        RedemptionRow redemption = new RedemptionRow(
                redemptionId, campaignId, userId, null, null, orderId,
                "RESERVED", "ET", null,
                0L, 0L, 0L, null, Instant.now(), null,
                null, null, null, null, null,
                Instant.now(), Instant.now()
        );

        CampaignRow campaign = new CampaignRow(
                campaignId, "purchase-promo", "Purchase Promo", null,
                "PURCHASE", "ALL", "FREE_PREMIUM",
                null, null, null,
                UUID.randomUUID(), null, "ET",
                30, null,
                100, 1, 0, 0,
                0, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600),
                "ACTIVE", null, 50L, null, null, null
        );

        when(promotionRepo.findReservedRedemptionByOrderId(orderId)).thenReturn(Optional.of(redemption));
        when(promotionRepo.findCampaignById(campaignId)).thenReturn(Optional.of(campaign));

        service.fulfillPurchasePromotion(orderId, subId);

        verify(promotionRepo).fulfillRedemption(redemptionId, subId);
        verify(promotionRepo).fulfillReservation(campaignId);
        verify(creditService).grantPromotionCredits(eq(userId), eq(50L), eq(redemptionId), anyString());
    }

    @Test
    void fulfillPurchasePromotion_withoutIncludedCredits_doesNotGrantCredits() {
        service = new PromotionFulfillmentService(promotionRepo, billingRepo, creditService);

        UUID orderId = UUID.randomUUID();
        UUID subId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        UUID redemptionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        RedemptionRow redemption = new RedemptionRow(
                redemptionId, campaignId, userId, null, null, orderId,
                "RESERVED", "ET", null,
                0L, 0L, 0L, null, Instant.now(), null,
                null, null, null, null, null,
                Instant.now(), Instant.now()
        );

        CampaignRow campaign = new CampaignRow(
                campaignId, "purchase-promo-no-credits", "Purchase Promo", null,
                "PURCHASE", "ALL", "DISCOUNT",
                "PERCENTAGE", 1000L, "ETB",
                UUID.randomUUID(), null, "ET",
                30, null,
                100, 1, 0, 0,
                0, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600),
                "ACTIVE", null, null, null, null, null
        );

        when(promotionRepo.findReservedRedemptionByOrderId(orderId)).thenReturn(Optional.of(redemption));
        when(promotionRepo.findCampaignById(campaignId)).thenReturn(Optional.of(campaign));

        service.fulfillPurchasePromotion(orderId, subId);

        verify(promotionRepo).fulfillRedemption(redemptionId, subId);
        verify(promotionRepo).fulfillReservation(campaignId);
        verifyNoInteractions(creditService);
    }

    @Test
    void fulfillPurchasePromotion_noRedemption_isNoOp() {
        service = new PromotionFulfillmentService(promotionRepo, billingRepo, creditService);

        UUID orderId = UUID.randomUUID();
        when(promotionRepo.findReservedRedemptionByOrderId(orderId)).thenReturn(Optional.empty());

        service.fulfillPurchasePromotion(orderId, null);

        verify(promotionRepo, never()).fulfillRedemption(any(), any());
        verifyNoInteractions(creditService);
    }

    @Test
    void fulfillPurchasePromotion_expiredCampaign_doesNotGrantCredits() {
        service = new PromotionFulfillmentService(promotionRepo, billingRepo, creditService);

        UUID orderId = UUID.randomUUID();
        UUID subId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        UUID redemptionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        RedemptionRow redemption = new RedemptionRow(
                redemptionId, campaignId, userId, null, null, orderId,
                "RESERVED", "ET", null,
                0L, 0L, 0L, null, Instant.now(), null,
                null, null, null, null, null,
                Instant.now(), Instant.now()
        );

        CampaignRow campaign = new CampaignRow(
                campaignId, "expired-promo", "Expired Promo", null,
                "PURCHASE", "ALL", "DISCOUNT",
                "PERCENTAGE", 1000L, "ETB",
                UUID.randomUUID(), null, "ET",
                30, null,
                100, 1, 0, 0,
                0, Instant.now().minusSeconds(120), Instant.now().minusSeconds(60),
                "ACTIVE", null, 50L, null, null, null
        );

        when(promotionRepo.findReservedRedemptionByOrderId(orderId)).thenReturn(Optional.of(redemption));
        when(promotionRepo.findCampaignById(campaignId)).thenReturn(Optional.of(campaign));

        service.fulfillPurchasePromotion(orderId, subId);

        verify(promotionRepo).fulfillRedemption(redemptionId, subId);
        verify(promotionRepo).fulfillReservation(campaignId);
        verifyNoInteractions(creditService);
    }

    @Test
    void fulfillPurchasePromotion_inactiveCampaign_doesNotGrantCredits() {
        service = new PromotionFulfillmentService(promotionRepo, billingRepo, creditService);

        UUID orderId = UUID.randomUUID();
        UUID subId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        UUID redemptionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        RedemptionRow redemption = new RedemptionRow(
                redemptionId, campaignId, userId, null, null, orderId,
                "RESERVED", "ET", null,
                0L, 0L, 0L, null, Instant.now(), null,
                null, null, null, null, null,
                Instant.now(), Instant.now()
        );

        CampaignRow campaign = new CampaignRow(
                campaignId, "inactive-promo", "Inactive Promo", null,
                "PURCHASE", "ALL", "FREE_PREMIUM",
                null, null, null,
                UUID.randomUUID(), null, "ET",
                30, null,
                100, 1, 0, 0,
                0, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600),
                "PAUSED", null, 50L, null, null, null
        );

        when(promotionRepo.findReservedRedemptionByOrderId(orderId)).thenReturn(Optional.of(redemption));
        when(promotionRepo.findCampaignById(campaignId)).thenReturn(Optional.of(campaign));

        service.fulfillPurchasePromotion(orderId, subId);

        verify(promotionRepo).fulfillRedemption(redemptionId, subId);
        verify(promotionRepo).fulfillReservation(campaignId);
        verifyNoInteractions(creditService);
    }
}
