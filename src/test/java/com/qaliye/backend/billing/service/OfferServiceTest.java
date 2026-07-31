package com.qaliye.backend.billing.service;

import com.qaliye.backend.billing.dto.OfferDto;
import com.qaliye.backend.billing.dto.PaymentMethodDto;
import com.qaliye.backend.billing.dto.PaymentOptionsResponse;
import com.qaliye.backend.billing.repository.BillingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OfferServiceTest {

    @Mock BillingRepository billingRepo;
    @Mock BillingMarketResolver marketResolver;
    @Mock PromotionEligibilityService promotionEligibilityService;
    OfferService service;

    UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new OfferService(billingRepo, marketResolver, promotionEligibilityService);
        lenient().when(billingRepo.getUnlimitedEntitlementTypes(any())).thenReturn(java.util.Set.of());
        lenient().when(promotionEligibilityService.findBestPurchasePromotion(any(), any(), anyInt(), any(), any()))
                .thenReturn(Optional.empty());
        lenient().when(promotionEligibilityService.findClaimablePromotions(any(), any(), any()))
                .thenReturn(List.of());
    }

    @Test
    void getOffers_etMarket_returnsOffersWithMethodCount() {
        when(marketResolver.resolveMarket(userId, "ANDROID"))
                .thenReturn(new BillingMarketResolver.MarketResult("ET", "ET", "ANDROID", false));
        when(billingRepo.countActivePaymentMethods("ET", "ANDROID")).thenReturn(2);
        when(billingRepo.findActiveOffers("ANDROID", "ET")).thenReturn(List.of(
                createOffer("PREMIUM_MONTHLY"),
                createOffer("PREMIUM_YEARLY")
        ));

        List<OfferDto> offers = service.getOffers(userId, "ANDROID");

        assertThat(offers).hasSize(2);
        assertThat(offers.get(0).hasAvailablePaymentMethods()).isTrue();
        assertThat(offers.get(0).availablePaymentMethodCount()).isEqualTo(2);
    }

    @Test
    void getOffers_noMethodsInMarket_flaggedAsUnavailable() {
        when(marketResolver.resolveMarket(userId, "ANDROID"))
                .thenReturn(new BillingMarketResolver.MarketResult("ET", "ET", "ANDROID", false));
        when(billingRepo.countActivePaymentMethods("ET", "ANDROID")).thenReturn(0);
        when(billingRepo.findActiveOffers("ANDROID", "ET")).thenReturn(List.of(
                createOffer("PREMIUM_MONTHLY")
        ));

        List<OfferDto> offers = service.getOffers(userId, "ANDROID");

        assertThat(offers.get(0).hasAvailablePaymentMethods()).isFalse();
        assertThat(offers.get(0).availablePaymentMethodCount()).isZero();
    }

    @Test
    void getOffers_fallbackToGlobal_usesGlobalMarket() {
        when(marketResolver.resolveMarket(userId, "ANDROID"))
                .thenReturn(new BillingMarketResolver.MarketResult("ET", "GLOBAL", "ANDROID", true));
        when(billingRepo.countActivePaymentMethods("GLOBAL", "ANDROID")).thenReturn(1);
        when(billingRepo.findActiveOffers("ANDROID", "GLOBAL")).thenReturn(List.of(
                createOffer("PREMIUM_MONTHLY")
        ));

        List<OfferDto> offers = service.getOffers(userId, "ANDROID");

        assertThat(offers).hasSize(1);
    }

    @Test
    void getPaymentOptions_returnsMarketMetadataAndMethods() {
        UUID methodId = UUID.randomUUID();
        when(marketResolver.resolveMethodsMarket(userId, "ANDROID"))
                .thenReturn(new BillingMarketResolver.MarketResult("ET", "ET", "ANDROID", false));
        when(billingRepo.findActivePaymentMethods("ET", "ANDROID")).thenReturn(List.of(
                createMethodRow(methodId, "ONLINE_PAYMENT", "chapa", "CHAPA_CHECKOUT", 1),
                createMethodRow(UUID.randomUUID(), "MANUAL_TRANSFER", "telebirr", "BANK_TRANSFER", 2)
        ));

        PaymentOptionsResponse response = service.getPaymentOptions(userId, "ANDROID");

        assertThat(response.billingCountryCode()).isEqualTo("ET");
        assertThat(response.resolvedMarketCountryCode()).isEqualTo("ET");
        assertThat(response.fallbackToGlobal()).isFalse();
        assertThat(response.paymentMethods()).hasSize(2);
        assertThat(response.paymentMethods().get(0).id()).isEqualTo(methodId);
    }

    @Test
    void getPaymentOptions_fallbackToGlobal_flaggedInResponse() {
        when(marketResolver.resolveMethodsMarket(userId, "ANDROID"))
                .thenReturn(new BillingMarketResolver.MarketResult("ET", "GLOBAL", "ANDROID", true));
        when(billingRepo.findActivePaymentMethods("GLOBAL", "ANDROID")).thenReturn(List.of(
                createMethodRow(UUID.randomUUID(), "ONLINE_PAYMENT", "google", "GOOGLE_PLAY_BILLING", 1)
        ));

        PaymentOptionsResponse response = service.getPaymentOptions(userId, "ANDROID");

        assertThat(response.billingCountryCode()).isEqualTo("ET");
        assertThat(response.resolvedMarketCountryCode()).isEqualTo("GLOBAL");
        assertThat(response.fallbackToGlobal()).isTrue();
        assertThat(response.paymentMethods()).hasSize(1);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private BillingRepository.OfferRow createOffer(String productCode) {
        return new BillingRepository.OfferRow(
                UUID.randomUUID(), null, "ET", "ANDROID",
                "ETB", 49900, false,
                null, null, null,
                productCode, "MONTH", 1,
                null, null, null
        );
    }

    private BillingRepository.PaymentMethodRow createMethodRow(
            UUID id, String channel, String methodCode, String method, int order) {
        return new BillingRepository.PaymentMethodRow(
                id, "ET", "ANDROID",
                methodCode, "Pay via " + methodCode,
                channel, method,
                null, true, order,
                null, null
        );
    }
}
