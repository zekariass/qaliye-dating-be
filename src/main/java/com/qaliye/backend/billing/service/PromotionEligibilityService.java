package com.qaliye.backend.billing.service;

import com.qaliye.backend.billing.repository.PromotionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class PromotionEligibilityService {

    private static final Logger log = LoggerFactory.getLogger(PromotionEligibilityService.class);

    private final PromotionRepository promotionRepo;
    private final PromotionDiscountCalculator calculator;
    private final CountrySettingsService countrySettingsService;

    public PromotionEligibilityService(PromotionRepository promotionRepo,
                                        PromotionDiscountCalculator calculator,
                                        CountrySettingsService countrySettingsService) {
        this.promotionRepo = promotionRepo;
        this.calculator = calculator;
        this.countrySettingsService = countrySettingsService;
    }

    public record AppliedPromotion(
            PromotionRepository.CampaignRow campaign,
            PromotionDiscountCalculator.DiscountResult discount
    ) {}

    // ── PURCHASE: find best discount promotion for an offer ──────────────────

    public Optional<AppliedPromotion> findBestPurchasePromotion(
            UUID userId, UUID productId,
            int offerPriceMinor, String offerCurrency,
            String trustedCountry) {

        Instant now = Instant.now();
        List<PromotionRepository.CampaignRow> candidates =
                promotionRepo.findActivePurchaseCampaigns(productId, trustedCountry, now);

        List<AppliedPromotion> eligible = new ArrayList<>();
        for (var campaign : candidates) {
            if (!checkEligibility(userId, campaign, trustedCountry, now)) continue;
            try {
                PromotionDiscountCalculator.DiscountResult discount =
                        calculator.calculate(campaign, offerPriceMinor, offerCurrency);
                if (discount.discountAmountMinor() > 0) {
                    eligible.add(new AppliedPromotion(campaign, discount));
                }
            } catch (Exception e) {
                log.warn("Discount calculation failed for campaign={}: {}", campaign.id(), e.getMessage());
            }
        }

        return eligible.stream()
                .max(Comparator
                        .comparingInt((AppliedPromotion ap) -> ap.campaign().priority())
                        .thenComparingLong(ap -> ap.discount().discountAmountMinor())
                        .thenComparing(ap -> ap.campaign().endsAt() == null
                                ? Instant.MAX : ap.campaign().endsAt(),
                                Comparator.reverseOrder()));
    }

    // ── USER_CLAIM: find all claimable FREE_PREMIUM promotions ───────────────

    public List<PromotionRepository.CampaignRow> findClaimablePromotions(
            UUID userId, UUID subscriptionProductId, String trustedCountry) {

        Instant now = Instant.now();
        List<PromotionRepository.CampaignRow> candidates =
                promotionRepo.findActiveCampaignsByTriggerAndProduct(
                        "USER_CLAIM", subscriptionProductId, trustedCountry, now);

        List<PromotionRepository.CampaignRow> eligible = new ArrayList<>();
        for (var campaign : candidates) {
            if (!"FREE_PREMIUM".equals(campaign.benefitType())) continue;
            if (checkEligibility(userId, campaign, trustedCountry, now)) {
                eligible.add(campaign);
            }
        }
        return eligible;
    }

    // ── AUTO_ON_SIGNUP: find all qualifying campaigns for a new user ─────────

    public List<PromotionRepository.CampaignRow> findSignupPromotions(
            UUID userId, String trustedCountry) {

        Instant now = Instant.now();
        List<PromotionRepository.CampaignRow> candidates =
                promotionRepo.findActiveCampaignsByTrigger("AUTO_ON_SIGNUP", trustedCountry, now);

        List<PromotionRepository.CampaignRow> eligible = new ArrayList<>();
        for (var campaign : candidates) {
            if (!"FREE_PREMIUM".equals(campaign.benefitType())
                    && !"CREDITS".equals(campaign.benefitType())) continue;
            if (checkEligibility(userId, campaign, trustedCountry, now)) {
                eligible.add(campaign);
            }
        }
        eligible.sort(Comparator.comparingInt(PromotionRepository.CampaignRow::priority).reversed());
        return eligible;
    }

    // ── ALL: find all eligible promotions for a user across all products ─────

    public List<PromotionRepository.CampaignRow> findAllEligiblePromotions(UUID userId, String trustedCountry) {
        Instant now = Instant.now();
        CountrySettingsService.CountrySettings settings = countrySettingsService.getSettings(trustedCountry);
        List<PromotionRepository.CampaignRow> result = new ArrayList<>();

        List<PromotionRepository.CampaignRow> claimable =
                promotionRepo.findActiveCampaignsByTrigger("USER_CLAIM", trustedCountry, now);
        for (var campaign : claimable) {
            if (!"FREE_PREMIUM".equals(campaign.benefitType())
                    && !"CREDITS".equals(campaign.benefitType())) continue;
            if (!isAllowedByCountrySettings(campaign, settings)) continue;
            if (checkEligibility(userId, campaign, trustedCountry, now)) {
                result.add(campaign);
            }
        }

        List<PromotionRepository.CampaignRow> purchase =
                promotionRepo.findActiveCampaignsByTrigger("PURCHASE", trustedCountry, now);
        for (var campaign : purchase) {
            if (!"DISCOUNT".equals(campaign.benefitType())) continue;
            if (!isAllowedByCountrySettings(campaign, settings)) continue;
            if (checkEligibility(userId, campaign, trustedCountry, now)) {
                result.add(campaign);
            }
        }

        return result;
    }

    private boolean isAllowedByCountrySettings(PromotionRepository.CampaignRow campaign,
                                                CountrySettingsService.CountrySettings settings) {
        if (campaign.subscriptionProductId() != null && !settings.subscriptionEnabled()) return false;
        if (campaign.consumableProductId() != null && !settings.creditsEnabled()) return false;
        return true;
    }

    // ── Core eligibility check ───────────────────────────────────────────────

    public boolean checkEligibility(UUID userId,
                                     PromotionRepository.CampaignRow campaign,
                                     String trustedCountry,
                                     Instant now) {
        if (!"ACTIVE".equals(campaign.status())) return false;
        if (now.isBefore(campaign.startsAt())) return false;
        if (campaign.endsAt() != null && !now.isBefore(campaign.endsAt())) return false;

        if (!campaign.countryCode().equalsIgnoreCase(trustedCountry)
                && !"GLOBAL".equalsIgnoreCase(campaign.countryCode())) {
            return false;
        }

        if (!checkGenderEligibility(userId, campaign)) return false;

        if (!checkEligibilityType(userId, campaign, now)) return false;

        if (campaign.maxRedemptions() != null
                && (campaign.fulfilledCount() + campaign.reservedCount()) >= campaign.maxRedemptions()) {
            return false;
        }

        int userRedemptionCount = promotionRepo.countActiveRedemptionsForUser(campaign.id(), userId);
        if (userRedemptionCount >= campaign.maxRedemptionsPerUser()) {
            log.debug("Per-user redemption limit reached: campaign={} user={} count={}/{}",
                    campaign.id(), userId, userRedemptionCount, campaign.maxRedemptionsPerUser());
            return false;
        }

        log.debug("Promotion eligible: campaign={} user={} userRedemptions={}/{} globalUsed={}/{}",
                campaign.campaignKey(), userId, userRedemptionCount, campaign.maxRedemptionsPerUser(),
                campaign.fulfilledCount() + campaign.reservedCount(), campaign.maxRedemptions());

        return true;
    }

    private boolean checkGenderEligibility(UUID userId,
                                            PromotionRepository.CampaignRow campaign) {
        if (campaign.targetGender() == null) {
            return true;
        }
        Optional<String> userGender = promotionRepo.getUserGender(userId);
        if (userGender.isEmpty()) {
            return false;
        }
        return campaign.targetGender().equalsIgnoreCase(userGender.get());
    }

    private boolean checkEligibilityType(UUID userId,
                                          PromotionRepository.CampaignRow campaign,
                                          Instant now) {
        switch (campaign.eligibilityType()) {
            case "ANY_ELIGIBLE_USER" -> {
                return true;
            }
            case "NEW_USER" -> {
                Optional<Instant> createdAt = promotionRepo.getUserCreatedAt(userId);
                if (createdAt.isEmpty()) return false;
                int windowDays = campaign.newUserWindowDays() != null ? campaign.newUserWindowDays() : 30;
                return Duration.between(createdAt.get(), now).toDays() <= windowDays;
            }
            case "NEVER_SUBSCRIBED" -> {
                return !promotionRepo.hasAnySubscription(userId);
            }
            case "NO_ACTIVE_SUBSCRIPTION" -> {
                return !promotionRepo.hasActiveSubscription(userId);
            }
            default -> {
                log.warn("Unknown eligibility_type: {}", campaign.eligibilityType());
                return false;
            }
        }
    }
}
