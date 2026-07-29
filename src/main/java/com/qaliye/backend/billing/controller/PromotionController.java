package com.qaliye.backend.billing.controller;

import com.qaliye.backend.billing.dto.EligiblePromotionDto;
import com.qaliye.backend.billing.dto.RedeemPromotionResponse;
import com.qaliye.backend.billing.dto.UserRedemptionDto;
import com.qaliye.backend.billing.repository.PromotionRepository;
import com.qaliye.backend.billing.service.BillingMarketResolver;
import com.qaliye.backend.billing.service.PromotionEligibilityService;
import com.qaliye.backend.billing.service.PromotionService;
import com.qaliye.backend.common.CallerUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/billing/promotions")
public class PromotionController {

    private final PromotionService promotionService;
    private final PromotionEligibilityService eligibilityService;
    private final BillingMarketResolver marketResolver;
    private final PromotionRepository promotionRepo;

    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

    public PromotionController(PromotionService promotionService,
                                PromotionEligibilityService eligibilityService,
                                BillingMarketResolver marketResolver,
                                PromotionRepository promotionRepo) {
        this.promotionService = promotionService;
        this.eligibilityService = eligibilityService;
        this.marketResolver = marketResolver;
        this.promotionRepo = promotionRepo;
    }

    @GetMapping
    public List<EligiblePromotionDto> getEligiblePromotions() {
        UUID userId = CallerUtils.callerId();
        String trustedCountry = marketResolver.resolvePromotionCountry(userId);
        List<PromotionRepository.CampaignRow> campaigns =
                eligibilityService.findAllEligiblePromotions(userId, trustedCountry);
        return campaigns.stream().map(c -> toDto(c)).toList();
    }

    @GetMapping("/{campaignKey}")
    public EligiblePromotionDto getCampaign(@PathVariable String campaignKey) {
        UUID userId = CallerUtils.callerId();
        String trustedCountry = marketResolver.resolvePromotionCountry(userId);
        PromotionRepository.CampaignRow campaign = promotionRepo.findCampaignByKey(campaignKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "promotion_not_found"));
        boolean eligible = eligibilityService.checkEligibility(userId, campaign, trustedCountry, java.time.Instant.now());
        if (!eligible) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "promotion_not_eligible");
        }
        return toDto(campaign);
    }

    @GetMapping("/redemptions")
    public List<UserRedemptionDto> getMyRedemptions(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        UUID userId = CallerUtils.callerId();
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, pageSize));
        int offset = (safePage - 1) * safeSize;
        return promotionRepo.listRedemptionsByUser(userId, safeSize, offset)
                .stream().map(this::toUserRedemptionDto).toList();
    }

    @PostMapping("/{campaignKey}/redeem")
    @ResponseStatus(HttpStatus.CREATED)
    public RedeemPromotionResponse redeem(@PathVariable String campaignKey) {
        UUID callerId = CallerUtils.callerId();
        return promotionService.redeemPromotion(callerId, campaignKey);
    }

    private EligiblePromotionDto toDto(PromotionRepository.CampaignRow c) {
        boolean canRedeem = "USER_CLAIM".equals(c.triggerType()) && "FREE_PREMIUM".equals(c.benefitType());
        return new EligiblePromotionDto(
                c.id(), c.campaignKey(), c.name(), c.description(),
                c.triggerType(), c.benefitType(),
                c.discountType(), c.discountValue(), c.discountCurrency(),
                c.subscriptionProductId(), c.durationDays(),
                c.maxRedemptions(), c.reservedCount(), c.fulfilledCount(),
                c.endsAt() != null ? ISO_FMT.format(c.endsAt()) : null,
                c.targetGender(),
                canRedeem
        );
    }

    private UserRedemptionDto toUserRedemptionDto(PromotionRepository.UserRedemptionRow r) {
        return new UserRedemptionDto(
                r.id(), r.campaignId(), r.campaignKey(), r.campaignName(),
                r.benefitType(), r.durationDays(),
                r.subscriptionId(), r.paymentOrderId(),
                r.status(),
                r.originalAmountMinor(), r.discountAmountMinor(), r.finalAmountMinor(),
                r.currency(), r.reservedAt(), r.fulfilledAt(),
                r.cancelledAt(), r.expiredAt(), r.failureCode(),
                r.subscriptionStatus(), r.subscriptionPeriodEnd()
        );
    }
}
