package com.qaliye.backend.billing.service;

import com.qaliye.backend.billing.repository.PromotionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionEligibilityServiceTest {

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

    // ── helpers ──────────────────────────────────────────────────────────────

    private PromotionRepository.CampaignRow campaign(String eligibilityType, String discountType,
                                                      long discountValue, String discountCurrency,
                                                      Integer newUserWindowDays) {
        return new PromotionRepository.CampaignRow(
                UUID.randomUUID(), "promo-key", "Test Promo", null,
                "PURCHASE", eligibilityType, "DISCOUNT",
                discountType, discountValue, discountCurrency,
                productId, null, "ET",
                null, newUserWindowDays, 100, 1,
                0, 0, 10,
                Instant.now().minusSeconds(60), null,
                "ACTIVE", null, null, Instant.now(), Instant.now()
        );
    }

    private PromotionRepository.CampaignRow freePremiumCampaign(String eligibilityType,
                                                                  String triggerType,
                                                                  Integer newUserWindowDays) {
        return new PromotionRepository.CampaignRow(
                UUID.randomUUID(), "promo-fp", "Free Premium", null,
                triggerType, eligibilityType, "FREE_PREMIUM",
                null, null, null,
                productId, null, "ET",
                30, newUserWindowDays, 50, 1,
                0, 0, 5,
                Instant.now().minusSeconds(60), null,
                "ACTIVE", null, null, Instant.now(), Instant.now()
        );
    }

    // ── PURCHASE discount tests ───────────────────────────────────────────────

    @Test
    void findBestPurchasePromotion_anyEligibleUser_returnsDiscount() {
        var c = campaign("ANY_ELIGIBLE_USER", "PERCENTAGE", 2000, null, null);
        when(promotionRepo.findActivePurchaseCampaigns(eq(productId), eq("ET"), any()))
                .thenReturn(List.of(c));

        var result = service.findBestPurchasePromotion(userId, productId, 49900, "ETB", "ET");

        assertThat(result).isPresent();
        assertThat(result.get().discount().discountAmountMinor()).isEqualTo(9980);
        assertThat(result.get().discount().finalAmountMinor()).isEqualTo(39920);
    }

    @Test
    void findBestPurchasePromotion_noCampaigns_returnsEmpty() {
        when(promotionRepo.findActivePurchaseCampaigns(eq(productId), eq("ET"), any()))
                .thenReturn(List.of());

        var result = service.findBestPurchasePromotion(userId, productId, 49900, "ETB", "ET");

        assertThat(result).isEmpty();
    }

    @Test
    void findBestPurchasePromotion_expiredCampaign_returnsEmpty() {
        var c = new PromotionRepository.CampaignRow(
                UUID.randomUUID(), "k", "n", null,
                "PURCHASE", "ANY_ELIGIBLE_USER", "DISCOUNT",
                "PERCENTAGE", 2000L, null,
                productId, null, "ET",
                null, null, 100, 1,
                0, 0, 0,
                Instant.now().minusSeconds(7200),
                Instant.now().minusSeconds(60), // ends_at in the past
                "ACTIVE", null, null, Instant.now(), Instant.now()
        );
        when(promotionRepo.findActivePurchaseCampaigns(eq(productId), eq("ET"), any()))
                .thenReturn(List.of(c));

        var result = service.findBestPurchasePromotion(userId, productId, 49900, "ETB", "ET");

        assertThat(result).isEmpty();
    }

    @Test
    void findBestPurchasePromotion_newUser_withinWindow_eligible() {
        var c = campaign("NEW_USER", "PERCENTAGE", 5000, null, 30);
        when(promotionRepo.findActivePurchaseCampaigns(eq(productId), eq("ET"), any()))
                .thenReturn(List.of(c));
        when(promotionRepo.getUserCreatedAt(userId))
                .thenReturn(Optional.of(Instant.now().minus(5, ChronoUnit.DAYS)));

        var result = service.findBestPurchasePromotion(userId, productId, 49900, "ETB", "ET");

        assertThat(result).isPresent();
    }

    @Test
    void findBestPurchasePromotion_newUser_outsideWindow_notEligible() {
        var c = campaign("NEW_USER", "PERCENTAGE", 5000, null, 7);
        when(promotionRepo.findActivePurchaseCampaigns(eq(productId), eq("ET"), any()))
                .thenReturn(List.of(c));
        when(promotionRepo.getUserCreatedAt(userId))
                .thenReturn(Optional.of(Instant.now().minus(30, ChronoUnit.DAYS)));

        var result = service.findBestPurchasePromotion(userId, productId, 49900, "ETB", "ET");

        assertThat(result).isEmpty();
    }

    @Test
    void findBestPurchasePromotion_neverSubscribed_hasSubscription_notEligible() {
        var c = campaign("NEVER_SUBSCRIBED", "PERCENTAGE", 3000, null, null);
        when(promotionRepo.findActivePurchaseCampaigns(eq(productId), eq("ET"), any()))
                .thenReturn(List.of(c));
        when(promotionRepo.hasAnySubscription(userId)).thenReturn(true);

        var result = service.findBestPurchasePromotion(userId, productId, 49900, "ETB", "ET");

        assertThat(result).isEmpty();
    }

    @Test
    void findBestPurchasePromotion_neverSubscribed_noSubscription_eligible() {
        var c = campaign("NEVER_SUBSCRIBED", "PERCENTAGE", 3000, null, null);
        when(promotionRepo.findActivePurchaseCampaigns(eq(productId), eq("ET"), any()))
                .thenReturn(List.of(c));
        when(promotionRepo.hasAnySubscription(userId)).thenReturn(false);

        var result = service.findBestPurchasePromotion(userId, productId, 49900, "ETB", "ET");

        assertThat(result).isPresent();
    }

    @Test
    void findBestPurchasePromotion_noActiveSubscription_hasActive_notEligible() {
        var c = campaign("NO_ACTIVE_SUBSCRIPTION", "PERCENTAGE", 3000, null, null);
        when(promotionRepo.findActivePurchaseCampaigns(eq(productId), eq("ET"), any()))
                .thenReturn(List.of(c));
        when(promotionRepo.hasActiveSubscription(userId)).thenReturn(true);

        var result = service.findBestPurchasePromotion(userId, productId, 49900, "ETB", "ET");

        assertThat(result).isEmpty();
    }

    @Test
    void findBestPurchasePromotion_capacityExhausted_notEligible() {
        var c = new PromotionRepository.CampaignRow(
                UUID.randomUUID(), "k", "n", null,
                "PURCHASE", "ANY_ELIGIBLE_USER", "DISCOUNT",
                "PERCENTAGE", 2000L, null,
                productId, null, "ET",
                null, null, 10, 1,
                5, 5, 0, // reservedCount + fulfilledCount = max_redemptions
                Instant.now().minusSeconds(60), null,
                "ACTIVE", null, null, Instant.now(), Instant.now()
        );
        when(promotionRepo.findActivePurchaseCampaigns(eq(productId), eq("ET"), any()))
                .thenReturn(List.of(c));

        var result = service.findBestPurchasePromotion(userId, productId, 49900, "ETB", "ET");

        assertThat(result).isEmpty();
    }

    @Test
    void findBestPurchasePromotion_multipleCampaigns_returnsHigherDiscount() {
        var low = campaign("ANY_ELIGIBLE_USER", "PERCENTAGE", 1000, null, null);  // 10%
        var high = campaign("ANY_ELIGIBLE_USER", "PERCENTAGE", 3000, null, null); // 30%
        when(promotionRepo.findActivePurchaseCampaigns(eq(productId), eq("ET"), any()))
                .thenReturn(List.of(low, high));

        var result = service.findBestPurchasePromotion(userId, productId, 49900, "ETB", "ET");

        assertThat(result).isPresent();
        assertThat(result.get().campaign().discountValue()).isEqualTo(3000L);
    }

    // ── USER_CLAIM claimable promotions ───────────────────────────────────────

    @Test
    void findClaimablePromotions_eligible_returnsList() {
        var c = freePremiumCampaign("ANY_ELIGIBLE_USER", "USER_CLAIM", null);
        when(promotionRepo.findActiveCampaignsByTriggerAndProduct(eq("USER_CLAIM"), eq(productId), eq("ET"), any()))
                .thenReturn(List.of(c));

        var result = service.findClaimablePromotions(userId, productId, "ET");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).campaignKey()).isEqualTo("promo-fp");
    }

    @Test
    void findClaimablePromotions_discountBenefitType_excluded() {
        var c = campaign("ANY_ELIGIBLE_USER", "PERCENTAGE", 2000, null, null);
        when(promotionRepo.findActiveCampaignsByTriggerAndProduct(eq("USER_CLAIM"), eq(productId), eq("ET"), any()))
                .thenReturn(List.of(c));

        var result = service.findClaimablePromotions(userId, productId, "ET");

        assertThat(result).isEmpty();
    }

    @Test
    void findClaimablePromotions_neverSubscribed_hasSubscription_excluded() {
        var c = freePremiumCampaign("NEVER_SUBSCRIBED", "USER_CLAIM", null);
        when(promotionRepo.findActiveCampaignsByTriggerAndProduct(eq("USER_CLAIM"), eq(productId), eq("ET"), any()))
                .thenReturn(List.of(c));
        when(promotionRepo.hasAnySubscription(userId)).thenReturn(true);

        var result = service.findClaimablePromotions(userId, productId, "ET");

        assertThat(result).isEmpty();
    }

    // ── AUTO_ON_SIGNUP ────────────────────────────────────────────────────────

    @Test
    void findSignupPromotions_eligible_returnsSortedByPriority() {
        var low = freePremiumCampaign("ANY_ELIGIBLE_USER", "AUTO_ON_SIGNUP", null);
        var high = new PromotionRepository.CampaignRow(
                UUID.randomUUID(), "high-prio", "High Priority", null,
                "AUTO_ON_SIGNUP", "ANY_ELIGIBLE_USER", "FREE_PREMIUM",
                null, null, null,
                productId, null, "ET",
                30, null, 50, 1,
                0, 0, 100, // priority=100
                Instant.now().minusSeconds(60), null,
                "ACTIVE", null, null, Instant.now(), Instant.now()
        );
        when(promotionRepo.findActiveCampaignsByTriggerAndProduct(eq("AUTO_ON_SIGNUP"), eq(productId), eq("ET"), any()))
                .thenReturn(List.of(low, high));

        var result = service.findSignupPromotions(userId, productId, "ET");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).campaignKey()).isEqualTo("high-prio");
    }
}
