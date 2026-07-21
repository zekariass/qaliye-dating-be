package com.qaliye.backend.billing.service;

import com.qaliye.backend.billing.repository.PromotionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class PromotionSignupService {

    private static final Logger log = LoggerFactory.getLogger(PromotionSignupService.class);

    private final PromotionRepository promotionRepo;
    private final PromotionEligibilityService eligibilityService;
    private final PromotionFulfillmentService fulfillmentService;

    public PromotionSignupService(PromotionRepository promotionRepo,
                                   PromotionEligibilityService eligibilityService,
                                   PromotionFulfillmentService fulfillmentService) {
        this.promotionRepo = promotionRepo;
        this.eligibilityService = eligibilityService;
        this.fulfillmentService = fulfillmentService;
    }

    /**
     * Runs in a separate transaction so that failures here do not roll back registration.
     * Finds all active AUTO_ON_SIGNUP campaigns and grants the highest-priority eligible one.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applySignupPromotions(UUID userId, String trustedCountry) {
        try {
            List<PromotionRepository.CampaignRow> allProducts =
                    promotionRepo.findActiveCampaignsByTrigger("AUTO_ON_SIGNUP", trustedCountry, Instant.now());

            if (allProducts.isEmpty()) {
                log.info("AUTO_ON_SIGNUP: no active campaigns found for country={} user={}", trustedCountry, userId);
                return;
            }

            log.info("AUTO_ON_SIGNUP: found {} campaign(s) for country={} user={}", allProducts.size(), trustedCountry, userId);

            for (PromotionRepository.CampaignRow campaign : allProducts) {
                Instant now = Instant.now();
                if (!eligibilityService.checkEligibility(userId, campaign, trustedCountry, now)) {
                    log.info("AUTO_ON_SIGNUP: user={} not eligible for campaign={}", userId, campaign.campaignKey());
                    continue;
                }
                boolean reserved = promotionRepo.atomicReserveCapacity(
                        campaign.id(), userId, campaign.maxRedemptionsPerUser());
                if (!reserved) {
                    log.info("AUTO_ON_SIGNUP: capacity exhausted or per-user limit reached for campaign={} user={}", campaign.campaignKey(), userId);
                    continue;
                }
                String userGender = promotionRepo.getUserGender(userId).orElse(null);
                UUID redemptionId = null;
                try {
                    redemptionId = promotionRepo.insertRedemption(
                            campaign.id(), userId, null, null,
                            "RESERVED", trustedCountry, userGender,
                            0L, 0L, 0L, null
                    );
                    fulfillmentService.grantFreePromotion(userId, campaign, redemptionId, trustedCountry);
                    log.info("AUTO_ON_SIGNUP promotion applied: user={} campaign={}", userId, campaign.campaignKey());
                    break; // grant only the first (highest priority) eligible campaign
                } catch (Exception e) {
                    log.warn("AUTO_ON_SIGNUP grant failed for campaign={} user={}: {}", campaign.campaignKey(), userId, e.getMessage(), e);
                    if (redemptionId != null) {
                        promotionRepo.cancelRedemption(redemptionId, "grant_failed", e.getMessage());
                    }
                    promotionRepo.releaseReservation(campaign.id());
                }
            }
        } catch (Exception e) {
            log.error("AUTO_ON_SIGNUP processing failed for user={}: {}", userId, e.getMessage(), e);
        }
    }
}
