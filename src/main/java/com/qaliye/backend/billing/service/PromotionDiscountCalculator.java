package com.qaliye.backend.billing.service;

import com.qaliye.backend.billing.repository.PromotionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class PromotionDiscountCalculator {

    public record DiscountResult(
            long originalAmountMinor,
            long discountAmountMinor,
            long finalAmountMinor
    ) {}

    public DiscountResult calculate(PromotionRepository.CampaignRow campaign,
                                     int originalAmountMinor, String offerCurrency) {
        if (!"DISCOUNT".equals(campaign.benefitType())) {
            throw new IllegalArgumentException("Campaign is not a DISCOUNT type: " + campaign.id());
        }

        long original = originalAmountMinor;

        if ("PERCENTAGE".equals(campaign.discountType())) {
            long basisPoints = campaign.discountValue();
            BigDecimal discount = BigDecimal.valueOf(original)
                    .multiply(BigDecimal.valueOf(basisPoints))
                    .divide(BigDecimal.valueOf(10000), 0, RoundingMode.HALF_UP);
            long discountMinor = discount.longValue();
            long finalAmount = Math.max(0, original - discountMinor);
            return new DiscountResult(original, discountMinor, finalAmount);

        } else if ("FIXED".equals(campaign.discountType())) {
            if (campaign.discountCurrency() != null
                    && !campaign.discountCurrency().equalsIgnoreCase(offerCurrency)) {
                return new DiscountResult(original, 0, original);
            }
            long discountMinor = Math.min(original, campaign.discountValue());
            long finalAmount = original - discountMinor;
            return new DiscountResult(original, discountMinor, finalAmount);

        } else {
            throw new IllegalArgumentException("Unknown discountType: " + campaign.discountType());
        }
    }
}
