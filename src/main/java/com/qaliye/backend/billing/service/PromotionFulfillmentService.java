package com.qaliye.backend.billing.service;

import com.qaliye.backend.billing.repository.BillingRepository;
import com.qaliye.backend.billing.repository.PromotionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class PromotionFulfillmentService {

    private static final Logger log = LoggerFactory.getLogger(PromotionFulfillmentService.class);

    private final PromotionRepository promotionRepo;
    private final BillingRepository billingRepo;

    public PromotionFulfillmentService(PromotionRepository promotionRepo,
                                        BillingRepository billingRepo) {
        this.promotionRepo = promotionRepo;
        this.billingRepo = billingRepo;
    }

    /**
     * Grants a FREE_PREMIUM subscription for a promotion redemption.
     * Inserts a new subscription with provider=PROMOTION and updates the redemption to FULFILLED.
     * Must be called within an existing @Transactional context.
     *
     * @return the created subscriptionId
     */
    @CacheEvict(value = "subscriptionFeatures", key = "#userId")
    public UUID grantFreePromotion(UUID userId, PromotionRepository.CampaignRow campaign,
                                    UUID redemptionId, String eligibilityCountry) {
        if (!"FREE_PREMIUM".equals(campaign.benefitType())) {
            throw new IllegalArgumentException("Campaign " + campaign.id() + " is not FREE_PREMIUM");
        }
        if (campaign.durationDays() == null || campaign.durationDays() <= 0) {
            throw new IllegalStateException("Campaign " + campaign.id() + " has no valid durationDays");
        }

        // Guard: reject if user already has an active subscription
        if (promotionRepo.hasActiveSubscription(userId)) {
            throw new IllegalStateException("user_has_active_subscription");
        }

        UUID planId = promotionRepo.findPlanIdForProduct(campaign.subscriptionProductId())
                .orElseThrow(() -> new IllegalStateException(
                        "No plan found for product: " + campaign.subscriptionProductId()));

        Instant now = Instant.now();
        Instant periodEnd = now.plus(campaign.durationDays(), ChronoUnit.DAYS);

        UUID subId = billingRepo.insertSubscription(
                userId, planId, "PROMOTION",
                "promo-" + redemptionId, null,
                null, "ACTIVE", false,
                now, now, periodEnd
        );

        promotionRepo.fulfillRedemption(redemptionId, subId);
        promotionRepo.incrementFulfilled(campaign.id());

        log.info("FREE_PREMIUM granted: user={} campaign={} sub={} periodEnd={}",
                userId, campaign.campaignKey(), subId, periodEnd);

        return subId;
    }

}
