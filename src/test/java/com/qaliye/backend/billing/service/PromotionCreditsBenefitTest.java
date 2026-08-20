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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PromotionCreditsBenefitTest {

    @Mock PromotionRepository promotionRepo;
    @Mock PromotionEligibilityService eligibilityService;
    @Mock PromotionFulfillmentService fulfillmentService;
    @Mock BillingMarketResolver marketResolver;
    @Mock CountrySettingsService countrySettingsService;

    PromotionService promotionService;
    PromotionEligibilityService realEligibilityService;

    UUID userId = UUID.randomUUID();
    UUID campaignId = UUID.randomUUID();
    UUID redemptionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        promotionService = new PromotionService(promotionRepo, eligibilityService, fulfillmentService, marketResolver);
        realEligibilityService = new PromotionEligibilityService(promotionRepo, new PromotionDiscountCalculator(), countrySettingsService);
    }

    private PromotionRepository.CampaignRow creditsCampaign(String triggerType, long credits) {
        return new PromotionRepository.CampaignRow(
                campaignId, "credits-promo", "Credits Promo", null,
                triggerType, "ANY_ELIGIBLE_USER", "CREDITS",
                null, null, null,
                null, null, "ET",
                null, null, 100, 1,
                0, 0, 10,
                Instant.now().minusSeconds(60), null,
                "ACTIVE", null, credits, null, Instant.now(), Instant.now()
        );
    }

    // ── redeemPromotion: USER_CLAIM + CREDITS ─────────────────────────────────

    @Test
    void redeem_creditsCampaign_grantsCreditsAndReturnsResponse() {
        var campaign = creditsCampaign("USER_CLAIM", 50L);
        when(promotionRepo.findCampaignByKey("credits-promo")).thenReturn(Optional.of(campaign));
        when(marketResolver.resolvePromotionCountry(userId)).thenReturn("ET");
        when(eligibilityService.checkEligibility(eq(userId), eq(campaign), eq("ET"), any())).thenReturn(true);
        when(promotionRepo.atomicReserveCapacity(campaignId, userId, 1)).thenReturn(true);
        when(promotionRepo.insertRedemption(eq(campaignId), eq(userId), isNull(), isNull(),
                eq("RESERVED"), eq("ET"), isNull(), eq(0L), eq(0L), eq(0L), isNull()))
                .thenReturn(redemptionId);

        RedeemPromotionResponse response = promotionService.redeemPromotion(userId, "credits-promo");

        verify(fulfillmentService).grantCreditsPromotion(userId, campaign, redemptionId);
        verify(fulfillmentService, never()).grantFreePromotion(any(), any(), any(), any());
        assertThat(response.redemptionId()).isEqualTo(redemptionId);
        assertThat(response.creditsGranted()).isEqualTo(50L);
        assertThat(response.subscriptionId()).isNull();
        assertThat(response.campaignKey()).isEqualTo("credits-promo");
    }

    @Test
    void redeem_creditsCampaign_grantFails_cancelsRedemption() {
        var campaign = creditsCampaign("USER_CLAIM", 50L);
        when(promotionRepo.findCampaignByKey("credits-promo")).thenReturn(Optional.of(campaign));
        when(marketResolver.resolvePromotionCountry(userId)).thenReturn("ET");
        when(eligibilityService.checkEligibility(any(), any(), any(), any())).thenReturn(true);
        when(promotionRepo.atomicReserveCapacity(any(), any(), anyInt())).thenReturn(true);
        when(promotionRepo.insertRedemption(any(), any(), any(), any(), any(), any(), any(),
                anyLong(), anyLong(), anyLong(), any())).thenReturn(redemptionId);
        doThrow(new RuntimeException("credit_grant_error"))
                .when(fulfillmentService).grantCreditsPromotion(any(), any(), any());

        assertThatThrownBy(() -> promotionService.redeemPromotion(userId, "credits-promo"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("credit_grant_error");

        verify(promotionRepo).cancelRedemption(eq(redemptionId), eq("grant_failed"), anyString());
        verify(promotionRepo).releaseReservation(campaignId);
    }

    @Test
    void redeem_purchaseTrigger_creditsCampaign_throws400() {
        var campaign = creditsCampaign("PURCHASE", 50L);
        when(promotionRepo.findCampaignByKey("credits-promo")).thenReturn(Optional.of(campaign));

        assertThatThrownBy(() -> promotionService.redeemPromotion(userId, "credits-promo"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("promotion_not_claimable");
    }

    // ── findAllEligiblePromotions: USER_CLAIM + CREDITS ───────────────────────

    @Test
    void findAllEligiblePromotions_creditsCampaign_included() {
        var campaign = creditsCampaign("USER_CLAIM", 100L);
        when(countrySettingsService.getSettings("ET"))
                .thenReturn(new CountrySettingsService.CountrySettings("ET", true, true, false));
        when(promotionRepo.findActiveCampaignsByTrigger(eq("USER_CLAIM"), eq("ET"), any()))
                .thenReturn(List.of(campaign));
        when(promotionRepo.findActiveCampaignsByTrigger(eq("PURCHASE"), eq("ET"), any()))
                .thenReturn(List.of());

        var result = realEligibilityService.findAllEligiblePromotions(userId, "ET");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).benefitType()).isEqualTo("CREDITS");
        assertThat(result.get(0).includedCredits()).isEqualTo(100L);
    }

    // ── findSignupPromotions: AUTO_ON_SIGNUP + CREDITS ────────────────────────

    @Test
    void findSignupPromotions_creditsCampaign_included() {
        var campaign = creditsCampaign("AUTO_ON_SIGNUP", 200L);
        when(promotionRepo.findActiveCampaignsByTrigger(eq("AUTO_ON_SIGNUP"), eq("ET"), any()))
                .thenReturn(List.of(campaign));

        var result = realEligibilityService.findSignupPromotions(userId, "ET");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).benefitType()).isEqualTo("CREDITS");
        assertThat(result.get(0).includedCredits()).isEqualTo(200L);
    }

    @Test
    void findSignupPromotions_discountBenefitType_excluded() {
        var discountCampaign = new PromotionRepository.CampaignRow(
                UUID.randomUUID(), "disc-signup", "Disc Signup", null,
                "AUTO_ON_SIGNUP", "ANY_ELIGIBLE_USER", "DISCOUNT",
                "PERCENTAGE", 2000L, null,
                UUID.randomUUID(), null, "ET",
                null, null, 100, 1,
                0, 0, 10,
                Instant.now().minusSeconds(60), null,
                "ACTIVE", null, null, null, Instant.now(), Instant.now()
        );
        when(promotionRepo.findActiveCampaignsByTrigger(eq("AUTO_ON_SIGNUP"), eq("ET"), any()))
                .thenReturn(List.of(discountCampaign));

        var result = realEligibilityService.findSignupPromotions(userId, "ET");

        assertThat(result).isEmpty();
    }
}
