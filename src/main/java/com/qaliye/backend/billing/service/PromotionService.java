package com.qaliye.backend.billing.service;

import com.qaliye.backend.billing.dto.RedeemPromotionResponse;
import com.qaliye.backend.billing.repository.PromotionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class PromotionService {

    private static final Logger log = LoggerFactory.getLogger(PromotionService.class);

    private final PromotionRepository promotionRepo;
    private final PromotionEligibilityService eligibilityService;
    private final PromotionFulfillmentService fulfillmentService;
    private final BillingMarketResolver marketResolver;

    public PromotionService(PromotionRepository promotionRepo,
                             PromotionEligibilityService eligibilityService,
                             PromotionFulfillmentService fulfillmentService,
                             BillingMarketResolver marketResolver) {
        this.promotionRepo = promotionRepo;
        this.eligibilityService = eligibilityService;
        this.fulfillmentService = fulfillmentService;
        this.marketResolver = marketResolver;
    }

    @Transactional
    public RedeemPromotionResponse redeemPromotion(UUID userId, String campaignKey) {
        PromotionRepository.CampaignRow campaign = promotionRepo.findCampaignByKey(campaignKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "promotion_not_found"));

        if (!"USER_CLAIM".equals(campaign.triggerType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "promotion_not_claimable");
        }
        if (!"FREE_PREMIUM".equals(campaign.benefitType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "promotion_type_unsupported");
        }
        if (!"ACTIVE".equals(campaign.status())) {
            throw new ResponseStatusException(HttpStatus.GONE, "promotion_not_active");
        }

        Instant now = Instant.now();
        if (now.isBefore(campaign.startsAt())
                || (campaign.endsAt() != null && !now.isBefore(campaign.endsAt()))) {
            throw new ResponseStatusException(HttpStatus.GONE, "promotion_expired");
        }

        String trustedCountry = marketResolver.resolvePromotionCountry(userId);

        if (!eligibilityService.checkEligibility(userId, campaign, trustedCountry, now)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "promotion_not_eligible");
        }

        boolean reserved = promotionRepo.atomicReserveCapacity(
                campaign.id(), userId, campaign.maxRedemptionsPerUser());
        if (!reserved) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "promotion_capacity_exhausted");
        }

        String userGender = promotionRepo.getUserGender(userId).orElse(null);

        UUID redemptionId;
        try {
            redemptionId = promotionRepo.insertRedemption(
                    campaign.id(), userId, null, null,
                    "RESERVED", trustedCountry, userGender,
                    0L, 0L, 0L, null
            );
        } catch (DataIntegrityViolationException e) {
            promotionRepo.releaseReservation(campaign.id());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "promotion_already_redeemed");
        }

        UUID subId;
        try {
            subId = fulfillmentService.grantFreePromotion(userId, campaign, redemptionId, trustedCountry);
        } catch (Exception e) {
            promotionRepo.cancelRedemption(redemptionId, "grant_failed", e.getMessage());
            promotionRepo.releaseReservation(campaign.id());
            String reason = e.getMessage() != null ? e.getMessage() : "grant_failed";
            throw new ResponseStatusException(HttpStatus.CONFLICT, reason);
        }

        Instant periodEnd = now.plus(campaign.durationDays(), ChronoUnit.DAYS);

        log.info("USER_CLAIM redeemed: user={} campaign={} sub={}", userId, campaignKey, subId);

        return new RedeemPromotionResponse(
                redemptionId,
                subId,
                campaignKey,
                null,
                campaign.durationDays(),
                periodEnd,
                "Promotion redeemed successfully"
        );
    }
}
