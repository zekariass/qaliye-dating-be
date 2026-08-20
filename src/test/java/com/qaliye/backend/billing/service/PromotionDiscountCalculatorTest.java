package com.qaliye.backend.billing.service;

import com.qaliye.backend.billing.repository.PromotionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromotionDiscountCalculatorTest {

    PromotionDiscountCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new PromotionDiscountCalculator();
    }

    private PromotionRepository.CampaignRow campaign(String discountType, long discountValue,
                                                       String discountCurrency) {
        return new PromotionRepository.CampaignRow(
                UUID.randomUUID(), "test-key", "Test", null,
                "PURCHASE", "ANY_ELIGIBLE_USER", "DISCOUNT",
                discountType, discountValue, discountCurrency,
                UUID.randomUUID(), null, "ET",
                null, null, null, 1,
                0, 0, 0,
                Instant.now().minusSeconds(60), null,
                "ACTIVE", null, null, null, Instant.now(), Instant.now()
        );
    }

    @Test
    void percentage_20percent_calculatesCorrectly() {
        var c = campaign("PERCENTAGE", 2000, null); // 20%
        var result = calculator.calculate(c, 49900, "ETB");

        assertThat(result.originalAmountMinor()).isEqualTo(49900);
        assertThat(result.discountAmountMinor()).isEqualTo(9980);
        assertThat(result.finalAmountMinor()).isEqualTo(39920);
    }

    @Test
    void percentage_100percent_resultIsZero() {
        var c = campaign("PERCENTAGE", 10000, null); // 100%
        var result = calculator.calculate(c, 49900, "ETB");

        assertThat(result.finalAmountMinor()).isEqualTo(0);
    }

    @Test
    void percentage_10000basisPoints_exactlyZero() {
        var c = campaign("PERCENTAGE", 10000, null);
        var result = calculator.calculate(c, 100, "ETB");

        assertThat(result.discountAmountMinor()).isEqualTo(100);
        assertThat(result.finalAmountMinor()).isEqualTo(0);
    }

    @Test
    void fixed_sameCurrency_discountsExactAmount() {
        var c = campaign("FIXED", 10000, "ETB"); // 100 ETB off
        var result = calculator.calculate(c, 49900, "ETB");

        assertThat(result.discountAmountMinor()).isEqualTo(10000);
        assertThat(result.finalAmountMinor()).isEqualTo(39900);
    }

    @Test
    void fixed_currencyMismatch_noDiscount() {
        var c = campaign("FIXED", 10000, "USD");
        var result = calculator.calculate(c, 49900, "ETB");

        assertThat(result.discountAmountMinor()).isEqualTo(0);
        assertThat(result.finalAmountMinor()).isEqualTo(49900);
    }

    @Test
    void fixed_discountLargerThanPrice_capsAtOriginal() {
        var c = campaign("FIXED", 99999, "ETB");
        var result = calculator.calculate(c, 49900, "ETB");

        assertThat(result.discountAmountMinor()).isEqualTo(49900);
        assertThat(result.finalAmountMinor()).isEqualTo(0);
    }

    @Test
    void freePremium_benefitType_throwsIllegalArgument() {
        var c = new PromotionRepository.CampaignRow(
                UUID.randomUUID(), "k", "n", null,
                "USER_CLAIM", "ANY_ELIGIBLE_USER", "FREE_PREMIUM",
                null, null, null,
                UUID.randomUUID(), null, "ET",
                30, null, null, 1,
                0, 0, 0,
                Instant.now().minusSeconds(60), null,
                "ACTIVE", null, null, null, Instant.now(), Instant.now()
        );
        assertThatThrownBy(() -> calculator.calculate(c, 49900, "ETB"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
