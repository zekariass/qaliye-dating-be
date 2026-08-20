package com.qaliye.backend.billing.service;

import com.qaliye.backend.billing.repository.PromotionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionCountrySettingsTest {

    @Mock PromotionRepository promotionRepo;
    @Mock CountrySettingsService countrySettingsService;

    PromotionEligibilityService service;

    UUID userId = UUID.randomUUID();
    UUID subscriptionProductId = UUID.randomUUID();
    UUID consumableProductId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PromotionEligibilityService(promotionRepo, new PromotionDiscountCalculator(), countrySettingsService);
    }

    private CountrySettingsService.CountrySettings settings(boolean subEnabled, boolean creditsEnabled) {
        return new CountrySettingsService.CountrySettings("ET", subEnabled, creditsEnabled, false);
    }

    private PromotionRepository.CampaignRow subscriptionCampaign() {
        return new PromotionRepository.CampaignRow(
                UUID.randomUUID(), "sub-promo", "Sub Promo", null,
                "USER_CLAIM", "ANY_ELIGIBLE_USER", "FREE_PREMIUM",
                null, null, null,
                subscriptionProductId, null, "ET",
                30, null, 100, 1,
                0, 0, 10,
                Instant.now().minusSeconds(60), null,
                "ACTIVE", null, null, null, Instant.now(), Instant.now()
        );
    }

    private PromotionRepository.CampaignRow creditsCampaign() {
        return new PromotionRepository.CampaignRow(
                UUID.randomUUID(), "credits-promo", "Credits Promo", null,
                "PURCHASE", "ANY_ELIGIBLE_USER", "DISCOUNT",
                "PERCENTAGE", 2000L, null,
                null, consumableProductId, "ET",
                null, null, 100, 1,
                0, 0, 10,
                Instant.now().minusSeconds(60), null,
                "ACTIVE", null, null, null, Instant.now(), Instant.now()
        );
    }

    @Test
    void findAllEligiblePromotions_subscriptionDisabled_subscriptionCampaignExcluded() {
        when(countrySettingsService.getSettings("ET")).thenReturn(settings(false, true));
        when(promotionRepo.findActiveCampaignsByTrigger(eq("USER_CLAIM"), eq("ET"), any()))
                .thenReturn(List.of(subscriptionCampaign()));
        when(promotionRepo.findActiveCampaignsByTrigger(eq("PURCHASE"), eq("ET"), any()))
                .thenReturn(List.of());

        var result = service.findAllEligiblePromotions(userId, "ET");

        assertThat(result).isEmpty();
    }

    @Test
    void findAllEligiblePromotions_creditsDisabled_creditsCampaignExcluded() {
        when(countrySettingsService.getSettings("ET")).thenReturn(settings(true, false));
        when(promotionRepo.findActiveCampaignsByTrigger(eq("USER_CLAIM"), eq("ET"), any()))
                .thenReturn(List.of());
        when(promotionRepo.findActiveCampaignsByTrigger(eq("PURCHASE"), eq("ET"), any()))
                .thenReturn(List.of(creditsCampaign()));

        var result = service.findAllEligiblePromotions(userId, "ET");

        assertThat(result).isEmpty();
    }

    @Test
    void findAllEligiblePromotions_bothEnabled_allCampaignsReturned() {
        when(countrySettingsService.getSettings("ET")).thenReturn(settings(true, true));
        when(promotionRepo.findActiveCampaignsByTrigger(eq("USER_CLAIM"), eq("ET"), any()))
                .thenReturn(List.of(subscriptionCampaign()));
        when(promotionRepo.findActiveCampaignsByTrigger(eq("PURCHASE"), eq("ET"), any()))
                .thenReturn(List.of(creditsCampaign()));

        var result = service.findAllEligiblePromotions(userId, "ET");

        assertThat(result).hasSize(2);
    }

    @Test
    void findAllEligiblePromotions_subscriptionDisabled_creditsCampaignStillReturned() {
        when(countrySettingsService.getSettings("ET")).thenReturn(settings(false, true));
        when(promotionRepo.findActiveCampaignsByTrigger(eq("USER_CLAIM"), eq("ET"), any()))
                .thenReturn(List.of());
        when(promotionRepo.findActiveCampaignsByTrigger(eq("PURCHASE"), eq("ET"), any()))
                .thenReturn(List.of(creditsCampaign()));

        var result = service.findAllEligiblePromotions(userId, "ET");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).consumableProductId()).isEqualTo(consumableProductId);
    }

    @Test
    void findAllEligiblePromotions_creditsDisabled_subscriptionCampaignStillReturned() {
        when(countrySettingsService.getSettings("ET")).thenReturn(settings(true, false));
        when(promotionRepo.findActiveCampaignsByTrigger(eq("USER_CLAIM"), eq("ET"), any()))
                .thenReturn(List.of(subscriptionCampaign()));
        when(promotionRepo.findActiveCampaignsByTrigger(eq("PURCHASE"), eq("ET"), any()))
                .thenReturn(List.of());

        var result = service.findAllEligiblePromotions(userId, "ET");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).subscriptionProductId()).isEqualTo(subscriptionProductId);
    }
}
