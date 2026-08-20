package com.qaliye.backend.billing.service;

import com.qaliye.backend.billing.dto.RedeemPromotionResponse;
import com.qaliye.backend.billing.repository.PromotionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionServiceTest {

    @Mock PromotionRepository promotionRepo;
    @Mock PromotionEligibilityService eligibilityService;
    @Mock PromotionFulfillmentService fulfillmentService;
    @Mock BillingMarketResolver marketResolver;

    PromotionService service;

    UUID userId = UUID.randomUUID();
    UUID campaignId = UUID.randomUUID();
    UUID subId = UUID.randomUUID();
    UUID redemptionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PromotionService(promotionRepo, eligibilityService,
                fulfillmentService, marketResolver);
    }

    private PromotionRepository.CampaignRow activeCampaign(String triggerType, String benefitType) {
        return new PromotionRepository.CampaignRow(
                campaignId, "promo-key", "Test Promo", null,
                triggerType, "ANY_ELIGIBLE_USER", benefitType,
                null, null, null,
                UUID.randomUUID(), null, "ET",
                30, null, 100, 1,
                0, 0, 0,
                Instant.now().minusSeconds(60), null,
                "ACTIVE", null, null, null, Instant.now(), Instant.now()
        );
    }

    @Test
    void redeem_validCampaign_returnsRedemption() {
        var campaign = activeCampaign("USER_CLAIM", "FREE_PREMIUM");

        when(promotionRepo.findCampaignByKey("promo-key")).thenReturn(Optional.of(campaign));
        when(marketResolver.resolvePromotionCountry(userId)).thenReturn("ET");
        when(eligibilityService.checkEligibility(eq(userId), eq(campaign), eq("ET"), any()))
                .thenReturn(true);
        when(promotionRepo.atomicReserveCapacity(campaignId, userId, 1)).thenReturn(true);
        when(promotionRepo.insertRedemption(eq(campaignId), eq(userId), isNull(), isNull(),
                eq("RESERVED"), eq("ET"), isNull(), eq(0L), eq(0L), eq(0L), isNull()))
                .thenReturn(redemptionId);
        when(fulfillmentService.grantFreePromotion(eq(userId), eq(campaign), eq(redemptionId), eq("ET")))
                .thenReturn(subId);

        RedeemPromotionResponse response = service.redeemPromotion(userId, "promo-key");

        assertThat(response.redemptionId()).isEqualTo(redemptionId);
        assertThat(response.subscriptionId()).isEqualTo(subId);
        assertThat(response.campaignKey()).isEqualTo("promo-key");
        assertThat(response.durationDays()).isEqualTo(30);
        assertThat(response.periodEnd()).isAfter(Instant.now().minusSeconds(10));
    }

    @Test
    void redeem_campaignNotFound_throws404() {
        when(promotionRepo.findCampaignByKey("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.redeemPromotion(userId, "unknown"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("promotion_not_found");
    }

    @Test
    void redeem_wrongTriggerType_throws400() {
        var campaign = activeCampaign("AUTO_ON_SIGNUP", "FREE_PREMIUM");
        when(promotionRepo.findCampaignByKey("promo-key")).thenReturn(Optional.of(campaign));

        assertThatThrownBy(() -> service.redeemPromotion(userId, "promo-key"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("promotion_not_claimable");
    }

    @Test
    void redeem_inactiveCampaign_throws410() {
        var campaign = new PromotionRepository.CampaignRow(
                campaignId, "promo-key", "Test", null,
                "USER_CLAIM", "ANY_ELIGIBLE_USER", "FREE_PREMIUM",
                null, null, null,
                UUID.randomUUID(), null, "ET",
                30, null, 100, 1,
                0, 0, 0,
                Instant.now().minusSeconds(60), null,
                "PAUSED", null, null, null, Instant.now(), Instant.now()
        );
        when(promotionRepo.findCampaignByKey("promo-key")).thenReturn(Optional.of(campaign));

        assertThatThrownBy(() -> service.redeemPromotion(userId, "promo-key"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("promotion_not_active");
    }

    @Test
    void redeem_notEligible_throws422() {
        var campaign = activeCampaign("USER_CLAIM", "FREE_PREMIUM");
        when(promotionRepo.findCampaignByKey("promo-key")).thenReturn(Optional.of(campaign));
        when(marketResolver.resolvePromotionCountry(userId)).thenReturn("ET");
        when(eligibilityService.checkEligibility(eq(userId), eq(campaign), eq("ET"), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.redeemPromotion(userId, "promo-key"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(422));
    }

    @Test
    void redeem_capacityExhausted_throws409() {
        var campaign = activeCampaign("USER_CLAIM", "FREE_PREMIUM");
        when(promotionRepo.findCampaignByKey("promo-key")).thenReturn(Optional.of(campaign));
        when(marketResolver.resolvePromotionCountry(userId)).thenReturn("ET");
        when(eligibilityService.checkEligibility(any(), any(), any(), any())).thenReturn(true);
        when(promotionRepo.atomicReserveCapacity(campaignId, userId, 1)).thenReturn(false);

        assertThatThrownBy(() -> service.redeemPromotion(userId, "promo-key"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("promotion_capacity_exhausted");

        verify(fulfillmentService, never()).grantFreePromotion(any(), any(), any(), any());
    }

    @Test
    void redeem_grantFails_releasesReservationAndThrows409() {
        var campaign = activeCampaign("USER_CLAIM", "FREE_PREMIUM");
        when(promotionRepo.findCampaignByKey("promo-key")).thenReturn(Optional.of(campaign));
        when(marketResolver.resolvePromotionCountry(userId)).thenReturn("ET");
        when(eligibilityService.checkEligibility(any(), any(), any(), any())).thenReturn(true);
        when(promotionRepo.atomicReserveCapacity(any(), any(), anyInt())).thenReturn(true);
        when(promotionRepo.insertRedemption(any(), any(), any(), any(), any(), any(), any(),
                anyLong(), anyLong(), anyLong(), any())).thenReturn(redemptionId);
        when(fulfillmentService.grantFreePromotion(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("user_has_active_subscription"));

        assertThatThrownBy(() -> service.redeemPromotion(userId, "promo-key"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("user_has_active_subscription");

        verify(promotionRepo).cancelRedemption(eq(redemptionId), eq("grant_failed"), anyString());
        verify(promotionRepo).releaseReservation(campaignId);
    }
}
