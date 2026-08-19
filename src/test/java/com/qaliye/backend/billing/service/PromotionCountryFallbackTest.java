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
class PromotionCountryFallbackTest {

    @Mock PromotionRepository promotionRepo;

    PromotionDiscountCalculator calculator;
    PromotionEligibilityService service;

    UUID userId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        calculator = new PromotionDiscountCalculator();
        service = new PromotionEligibilityService(promotionRepo, calculator);
    }

    private PromotionRepository.CampaignRow campaign(String countryCode, String triggerType, String benefitType) {
        return new PromotionRepository.CampaignRow(
                UUID.randomUUID(), "promo-" + countryCode, "Promo " + countryCode, null,
                triggerType, "ANY_ELIGIBLE_USER", benefitType,
                null, null, null,
                productId, null, countryCode,
                30, null, 100, 1,
                0, 0, 10,
                Instant.now().minusSeconds(60), null,
                "ACTIVE", null, null, Instant.now(), Instant.now()
        );
    }

    private PromotionRepository.CampaignRow purchaseCampaign(String countryCode, long discountPct) {
        return new PromotionRepository.CampaignRow(
                UUID.randomUUID(), "purchase-" + countryCode, "Purchase " + countryCode, null,
                "PURCHASE", "ANY_ELIGIBLE_USER", "DISCOUNT",
                "PERCENTAGE", discountPct, null,
                productId, null, countryCode,
                null, null, 100, 1,
                0, 0, 10,
                Instant.now().minusSeconds(60), null,
                "ACTIVE", null, null, Instant.now(), Instant.now()
        );
    }

    // ── checkEligibility: GLOBAL campaigns eligible for ET users ─────────────

    @Test
    void checkEligibility_globalCampaign_etUser_returnsTrue() {
        var campaign = campaign("GLOBAL", "AUTO_ON_SIGNUP", "FREE_PREMIUM");

        boolean result = service.checkEligibility(userId, campaign, "ET", Instant.now());

        assertThat(result).isTrue();
    }

    @Test
    void checkEligibility_etCampaign_etUser_returnsTrue() {
        var campaign = campaign("ET", "AUTO_ON_SIGNUP", "FREE_PREMIUM");

        boolean result = service.checkEligibility(userId, campaign, "ET", Instant.now());

        assertThat(result).isTrue();
    }

    @Test
    void checkEligibility_etCampaign_globalUser_returnsFalse() {
        var campaign = campaign("ET", "AUTO_ON_SIGNUP", "FREE_PREMIUM");

        boolean result = service.checkEligibility(userId, campaign, "GLOBAL", Instant.now());

        assertThat(result).isFalse();
    }

    @Test
    void checkEligibility_globalCampaign_globalUser_returnsTrue() {
        var campaign = campaign("GLOBAL", "AUTO_ON_SIGNUP", "FREE_PREMIUM");

        boolean result = service.checkEligibility(userId, campaign, "GLOBAL", Instant.now());

        assertThat(result).isTrue();
    }

    // ── findSignupPromotions: ET user sees both ET and GLOBAL, ET first ──────

    @Test
    void findSignupPromotions_etUser_returnsBothEtAndGlobal() {
        var etCampaign = campaign("ET", "AUTO_ON_SIGNUP", "FREE_PREMIUM");
        var globalCampaign = campaign("GLOBAL", "AUTO_ON_SIGNUP", "FREE_PREMIUM");
        when(promotionRepo.findActiveCampaignsByTriggerAndProduct(eq("AUTO_ON_SIGNUP"), eq(productId), eq("ET"), any()))
                .thenReturn(List.of(etCampaign, globalCampaign));

        var result = service.findSignupPromotions(userId, productId, "ET");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).countryCode()).isEqualTo("ET");
        assertThat(result.get(1).countryCode()).isEqualTo("GLOBAL");
    }

    @Test
    void findSignupPromotions_globalUser_returnsOnlyGlobal() {
        var globalCampaign = campaign("GLOBAL", "AUTO_ON_SIGNUP", "FREE_PREMIUM");
        when(promotionRepo.findActiveCampaignsByTriggerAndProduct(eq("AUTO_ON_SIGNUP"), eq(productId), eq("GLOBAL"), any()))
                .thenReturn(List.of(globalCampaign));

        var result = service.findSignupPromotions(userId, productId, "GLOBAL");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).countryCode()).isEqualTo("GLOBAL");
    }

    // ── findClaimablePromotions: ET user sees both ET and GLOBAL ─────────────

    @Test
    void findClaimablePromotions_etUser_returnsBothEtAndGlobal() {
        var etCampaign = campaign("ET", "USER_CLAIM", "FREE_PREMIUM");
        var globalCampaign = campaign("GLOBAL", "USER_CLAIM", "FREE_PREMIUM");
        when(promotionRepo.findActiveCampaignsByTriggerAndProduct(eq("USER_CLAIM"), eq(productId), eq("ET"), any()))
                .thenReturn(List.of(etCampaign, globalCampaign));

        var result = service.findClaimablePromotions(userId, productId, "ET");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).countryCode()).isEqualTo("ET");
        assertThat(result.get(1).countryCode()).isEqualTo("GLOBAL");
    }

    @Test
    void findClaimablePromotions_globalUser_returnsOnlyGlobal() {
        var globalCampaign = campaign("GLOBAL", "USER_CLAIM", "FREE_PREMIUM");
        when(promotionRepo.findActiveCampaignsByTriggerAndProduct(eq("USER_CLAIM"), eq(productId), eq("GLOBAL"), any()))
                .thenReturn(List.of(globalCampaign));

        var result = service.findClaimablePromotions(userId, productId, "GLOBAL");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).countryCode()).isEqualTo("GLOBAL");
    }

    // ── findBestPurchasePromotion: ET user gets best from ET and GLOBAL ──────

    @Test
    void findBestPurchasePromotion_etUser_etCampaignHigherDiscount_preferred() {
        var etCampaign = purchaseCampaign("ET", 3000L);
        var globalCampaign = purchaseCampaign("GLOBAL", 1000L);
        when(promotionRepo.findActivePurchaseCampaigns(eq(productId), eq("ET"), any()))
                .thenReturn(List.of(etCampaign, globalCampaign));

        var result = service.findBestPurchasePromotion(userId, productId, 49900, "ETB", "ET");

        assertThat(result).isPresent();
        assertThat(result.get().campaign().countryCode()).isEqualTo("ET");
    }

    @Test
    void findBestPurchasePromotion_etUser_onlyGlobalAvailable_returnsGlobal() {
        var globalCampaign = purchaseCampaign("GLOBAL", 2000L);
        when(promotionRepo.findActivePurchaseCampaigns(eq(productId), eq("ET"), any()))
                .thenReturn(List.of(globalCampaign));

        var result = service.findBestPurchasePromotion(userId, productId, 49900, "ETB", "ET");

        assertThat(result).isPresent();
        assertThat(result.get().campaign().countryCode()).isEqualTo("GLOBAL");
    }

    @Test
    void findBestPurchasePromotion_globalUser_doesNotSeeEtCampaign() {
        var globalCampaign = purchaseCampaign("GLOBAL", 2000L);
        when(promotionRepo.findActivePurchaseCampaigns(eq(productId), eq("GLOBAL"), any()))
                .thenReturn(List.of(globalCampaign));

        var result = service.findBestPurchasePromotion(userId, productId, 49900, "ETB", "GLOBAL");

        assertThat(result).isPresent();
        assertThat(result.get().campaign().countryCode()).isEqualTo("GLOBAL");
    }

    // ── findAllEligiblePromotions: ET user sees both ET and GLOBAL ───────────

    @Test
    void findAllEligiblePromotions_etUser_returnsBothEtAndGlobal() {
        var etCampaign = campaign("ET", "USER_CLAIM", "FREE_PREMIUM");
        var globalCampaign = campaign("GLOBAL", "USER_CLAIM", "FREE_PREMIUM");
        when(promotionRepo.findActiveCampaignsByTrigger(eq("USER_CLAIM"), eq("ET"), any()))
                .thenReturn(List.of(etCampaign, globalCampaign));
        when(promotionRepo.findActiveCampaignsByTrigger(eq("PURCHASE"), eq("ET"), any()))
                .thenReturn(List.of());

        var result = service.findAllEligiblePromotions(userId, "ET");

        assertThat(result).hasSize(2);
        assertThat(result).anySatisfy(c -> assertThat(c.countryCode()).isEqualTo("ET"));
        assertThat(result).anySatisfy(c -> assertThat(c.countryCode()).isEqualTo("GLOBAL"));
    }

    @Test
    void findAllEligiblePromotions_globalUser_returnsOnlyGlobal() {
        var globalCampaign = campaign("GLOBAL", "USER_CLAIM", "FREE_PREMIUM");
        when(promotionRepo.findActiveCampaignsByTrigger(eq("USER_CLAIM"), eq("GLOBAL"), any()))
                .thenReturn(List.of(globalCampaign));
        when(promotionRepo.findActiveCampaignsByTrigger(eq("PURCHASE"), eq("GLOBAL"), any()))
                .thenReturn(List.of());

        var result = service.findAllEligiblePromotions(userId, "GLOBAL");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).countryCode()).isEqualTo("GLOBAL");
    }
}
