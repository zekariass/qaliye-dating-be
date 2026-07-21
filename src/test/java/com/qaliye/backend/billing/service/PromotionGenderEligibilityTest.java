package com.qaliye.backend.billing.service;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionGenderEligibilityTest {

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

    private PromotionRepository.CampaignRow genderCampaign(String targetGender) {
        return new PromotionRepository.CampaignRow(
                UUID.randomUUID(), "gender-promo", "Gender Promo", null,
                "USER_CLAIM", "ANY_ELIGIBLE_USER", "FREE_PREMIUM",
                null, null, null,
                productId, "ET",
                30, null, 100, 1,
                0, 0, 10,
                Instant.now().minusSeconds(60), null,
                "ACTIVE", targetGender, null, Instant.now(), Instant.now()
        );
    }

    private PromotionRepository.CampaignRow genderPurchaseCampaign(String targetGender) {
        return new PromotionRepository.CampaignRow(
                UUID.randomUUID(), "gender-purchase", "Gender Purchase", null,
                "PURCHASE", "ANY_ELIGIBLE_USER", "DISCOUNT",
                "PERCENTAGE", 2000L, null,
                productId, "ET",
                null, null, 100, 1,
                0, 0, 10,
                Instant.now().minusSeconds(60), null,
                "ACTIVE", targetGender, null, Instant.now(), Instant.now()
        );
    }

    // ── Gender matching ──────────────────────────────────────────────────────

    @Test
    void checkEligibility_genderMatches_returnsTrue() {
        var campaign = genderCampaign("MALE");
        when(promotionRepo.getUserGender(userId)).thenReturn(Optional.of("MALE"));

        boolean result = service.checkEligibility(userId, campaign, "ET", Instant.now());

        assertThat(result).isTrue();
    }

    @Test
    void checkEligibility_genderMismatch_returnsFalse() {
        var campaign = genderCampaign("MALE");
        when(promotionRepo.getUserGender(userId)).thenReturn(Optional.of("FEMALE"));

        boolean result = service.checkEligibility(userId, campaign, "ET", Instant.now());

        assertThat(result).isFalse();
    }

    // ── Missing gender ───────────────────────────────────────────────────────

    @Test
    void checkEligibility_userHasNoGender_targetGenderSet_returnsFalse() {
        var campaign = genderCampaign("FEMALE");
        when(promotionRepo.getUserGender(userId)).thenReturn(Optional.empty());

        boolean result = service.checkEligibility(userId, campaign, "ET", Instant.now());

        assertThat(result).isFalse();
    }

    @Test
    void checkEligibility_userGenderNull_targetGenderSet_returnsFalse() {
        var campaign = genderCampaign("MALE");
        when(promotionRepo.getUserGender(userId)).thenReturn(Optional.ofNullable(null));

        boolean result = service.checkEligibility(userId, campaign, "ET", Instant.now());

        assertThat(result).isFalse();
    }

    // ── Unrestricted campaigns (target_gender is NULL) ───────────────────────

    @Test
    void checkEligibility_targetGenderNull_maleUser_returnsTrue() {
        var campaign = genderCampaign(null);
        // getUserGender should not even be called for null target_gender

        boolean result = service.checkEligibility(userId, campaign, "ET", Instant.now());

        assertThat(result).isTrue();
    }

    @Test
    void checkEligibility_targetGenderNull_femaleUser_returnsTrue() {
        var campaign = genderCampaign(null);

        boolean result = service.checkEligibility(userId, campaign, "ET", Instant.now());

        assertThat(result).isTrue();
    }

    @Test
    void checkEligibility_targetGenderNull_userWithNoGender_returnsTrue() {
        var campaign = genderCampaign(null);

        boolean result = service.checkEligibility(userId, campaign, "ET", Instant.now());

        assertThat(result).isTrue();
    }

    // ── Case-insensitive matching ────────────────────────────────────────────

    @Test
    void checkEligibility_genderCaseInsensitive_returnsTrue() {
        var campaign = genderCampaign("male");
        when(promotionRepo.getUserGender(userId)).thenReturn(Optional.of("MALE"));

        boolean result = service.checkEligibility(userId, campaign, "ET", Instant.now());

        assertThat(result).isTrue();
    }

    // ── findClaimablePromotions filters by gender ────────────────────────────

    @Test
    void findClaimablePromotions_maleUser_maleCampaignIncluded_femaleExcluded() {
        var maleCampaign = genderCampaign("MALE");
        var femaleCampaign = genderCampaign("FEMALE");
        when(promotionRepo.findActiveCampaignsByTriggerAndProduct(eq("USER_CLAIM"), eq(productId), eq("ET"), any()))
                .thenReturn(List.of(maleCampaign, femaleCampaign));
        when(promotionRepo.getUserGender(userId)).thenReturn(Optional.of("MALE"));

        var result = service.findClaimablePromotions(userId, productId, "ET");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).targetGender()).isEqualTo("MALE");
    }

    @Test
    void findClaimablePromotions_femaleUser_femaleCampaignIncluded_maleExcluded() {
        var maleCampaign = genderCampaign("MALE");
        var femaleCampaign = genderCampaign("FEMALE");
        when(promotionRepo.findActiveCampaignsByTriggerAndProduct(eq("USER_CLAIM"), eq(productId), eq("ET"), any()))
                .thenReturn(List.of(maleCampaign, femaleCampaign));
        when(promotionRepo.getUserGender(userId)).thenReturn(Optional.of("FEMALE"));

        var result = service.findClaimablePromotions(userId, productId, "ET");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).targetGender()).isEqualTo("FEMALE");
    }

    @Test
    void findClaimablePromotions_nullGenderUser_onlyUnrestrictedCampaigns() {
        var maleCampaign = genderCampaign("MALE");
        var unrestrictedCampaign = genderCampaign(null);
        when(promotionRepo.findActiveCampaignsByTriggerAndProduct(eq("USER_CLAIM"), eq(productId), eq("ET"), any()))
                .thenReturn(List.of(maleCampaign, unrestrictedCampaign));
        when(promotionRepo.getUserGender(userId)).thenReturn(Optional.empty());

        var result = service.findClaimablePromotions(userId, productId, "ET");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).targetGender()).isNull();
    }

    // ── findBestPurchasePromotion filters by gender ──────────────────────────

    @Test
    void findBestPurchasePromotion_maleUser_maleCampaignEligible() {
        var campaign = genderPurchaseCampaign("MALE");
        when(promotionRepo.findActivePurchaseCampaigns(eq(productId), eq("ET"), any()))
                .thenReturn(List.of(campaign));
        when(promotionRepo.getUserGender(userId)).thenReturn(Optional.of("MALE"));

        var result = service.findBestPurchasePromotion(userId, productId, 49900, "ETB", "ET");

        assertThat(result).isPresent();
    }

    @Test
    void findBestPurchasePromotion_maleUser_femaleCampaignNotEligible() {
        var campaign = genderPurchaseCampaign("FEMALE");
        when(promotionRepo.findActivePurchaseCampaigns(eq(productId), eq("ET"), any()))
                .thenReturn(List.of(campaign));
        when(promotionRepo.getUserGender(userId)).thenReturn(Optional.of("MALE"));

        var result = service.findBestPurchasePromotion(userId, productId, 49900, "ETB", "ET");

        assertThat(result).isEmpty();
    }

    // ── findSignupPromotions filters by gender ───────────────────────────────

    @Test
    void findSignupPromotions_femaleUser_onlyFemaleAndUnrestricted() {
        var maleCampaign = genderCampaign("MALE");
        maleCampaign = new PromotionRepository.CampaignRow(
                maleCampaign.id(), maleCampaign.campaignKey(), maleCampaign.name(), maleCampaign.description(),
                "AUTO_ON_SIGNUP", maleCampaign.eligibilityType(), maleCampaign.benefitType(),
                maleCampaign.discountType(), maleCampaign.discountValue(), maleCampaign.discountCurrency(),
                maleCampaign.subscriptionProductId(), maleCampaign.countryCode(),
                maleCampaign.durationDays(), maleCampaign.newUserWindowDays(),
                maleCampaign.maxRedemptions(), maleCampaign.maxRedemptionsPerUser(),
                maleCampaign.reservedCount(), maleCampaign.fulfilledCount(),
                maleCampaign.priority(), maleCampaign.startsAt(), maleCampaign.endsAt(),
                maleCampaign.status(), maleCampaign.targetGender(), maleCampaign.createdByUserId(),
                maleCampaign.createdAt(), maleCampaign.updatedAt()
        );
        var femaleCampaign = genderCampaign("FEMALE");
        femaleCampaign = new PromotionRepository.CampaignRow(
                femaleCampaign.id(), femaleCampaign.campaignKey(), femaleCampaign.name(), femaleCampaign.description(),
                "AUTO_ON_SIGNUP", femaleCampaign.eligibilityType(), femaleCampaign.benefitType(),
                femaleCampaign.discountType(), femaleCampaign.discountValue(), femaleCampaign.discountCurrency(),
                femaleCampaign.subscriptionProductId(), femaleCampaign.countryCode(),
                femaleCampaign.durationDays(), femaleCampaign.newUserWindowDays(),
                femaleCampaign.maxRedemptions(), femaleCampaign.maxRedemptionsPerUser(),
                femaleCampaign.reservedCount(), femaleCampaign.fulfilledCount(),
                femaleCampaign.priority(), femaleCampaign.startsAt(), femaleCampaign.endsAt(),
                femaleCampaign.status(), femaleCampaign.targetGender(), femaleCampaign.createdByUserId(),
                femaleCampaign.createdAt(), femaleCampaign.updatedAt()
        );
        when(promotionRepo.findActiveCampaignsByTriggerAndProduct(eq("AUTO_ON_SIGNUP"), eq(productId), eq("ET"), any()))
                .thenReturn(List.of(maleCampaign, femaleCampaign));
        when(promotionRepo.getUserGender(userId)).thenReturn(Optional.of("FEMALE"));

        var result = service.findSignupPromotions(userId, productId, "ET");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).targetGender()).isEqualTo("FEMALE");
    }

    // ── Separate capacity per gender campaign ────────────────────────────────

    @Test
    void separateCapacity_maleCampaignFull_femaleCampaignStillAvailable() {
        var maleFull = new PromotionRepository.CampaignRow(
                UUID.randomUUID(), "male-full", "Male Full", null,
                "USER_CLAIM", "ANY_ELIGIBLE_USER", "FREE_PREMIUM",
                null, null, null,
                productId, "ET",
                30, null, 10, 1,
                5, 5, 10, // reserved + fulfilled = max
                Instant.now().minusSeconds(60), null,
                "ACTIVE", "MALE", null, Instant.now(), Instant.now()
        );
        var femaleAvailable = new PromotionRepository.CampaignRow(
                UUID.randomUUID(), "female-avail", "Female Available", null,
                "USER_CLAIM", "ANY_ELIGIBLE_USER", "FREE_PREMIUM",
                null, null, null,
                productId, "ET",
                30, null, 10, 1,
                0, 0, 10,
                Instant.now().minusSeconds(60), null,
                "ACTIVE", "FEMALE", null, Instant.now(), Instant.now()
        );
        when(promotionRepo.findActiveCampaignsByTriggerAndProduct(eq("USER_CLAIM"), eq(productId), eq("ET"), any()))
                .thenReturn(List.of(maleFull, femaleAvailable));
        when(promotionRepo.getUserGender(userId)).thenReturn(Optional.of("FEMALE"));

        var result = service.findClaimablePromotions(userId, productId, "ET");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).campaignKey()).isEqualTo("female-avail");
    }

    @Test
    void separateCapacity_femaleUser_maleFullCampaignNotVisible() {
        var maleFull = new PromotionRepository.CampaignRow(
                UUID.randomUUID(), "male-full", "Male Full", null,
                "USER_CLAIM", "ANY_ELIGIBLE_USER", "FREE_PREMIUM",
                null, null, null,
                productId, "ET",
                30, null, 10, 1,
                5, 5, 10,
                Instant.now().minusSeconds(60), null,
                "ACTIVE", "MALE", null, Instant.now(), Instant.now()
        );
        when(promotionRepo.findActiveCampaignsByTriggerAndProduct(eq("USER_CLAIM"), eq(productId), eq("ET"), any()))
                .thenReturn(List.of(maleFull));
        when(promotionRepo.getUserGender(userId)).thenReturn(Optional.of("FEMALE"));

        var result = service.findClaimablePromotions(userId, productId, "ET");

        assertThat(result).isEmpty();
    }
}
