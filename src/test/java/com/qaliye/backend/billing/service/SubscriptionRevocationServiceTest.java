package com.qaliye.backend.billing.service;

import com.qaliye.backend.billing.repository.BillingRepository;
import com.qaliye.backend.billing.repository.CreditLotRepository;
import com.qaliye.backend.billing.repository.PromotionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubscriptionRevocationServiceTest {

    @Mock BillingRepository billingRepo;
    @Mock CreditLotRepository creditLotRepo;
    @Mock PromotionRepository promotionRepo;
    @Mock CacheManager cacheManager;
    @Mock Cache cache;

    SubscriptionRevocationService service;

    UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SubscriptionRevocationService(
                billingRepo, creditLotRepo, promotionRepo, cacheManager);
    }

    private BillingRepository.ActiveSubscriptionRow sub(String provider) {
        return new BillingRepository.ActiveSubscriptionRow(
                UUID.randomUUID(), "sub-123", provider, "ACTIVE", true);
    }

    @Test
    void revokeAll_withActiveSubscription_cancelsSubscription() {
        when(billingRepo.findActiveSubscriptionsForUser(userId))
                .thenReturn(List.of(sub("CHAPA")));
        when(billingRepo.cancelAllActiveSubscriptions(userId)).thenReturn(1);
        lenient().when(cacheManager.getCache("subscriptionFeatures")).thenReturn(cache);

        service.revokeAll(userId);

        verify(billingRepo).cancelAllActiveSubscriptions(userId);
    }

    @Test
    void revokeAll_cancelsPendingOrders() {
        when(billingRepo.findActiveSubscriptionsForUser(userId)).thenReturn(List.of());
        when(billingRepo.cancelPendingOrders(userId)).thenReturn(2);
        lenient().when(cacheManager.getCache("subscriptionFeatures")).thenReturn(cache);

        service.revokeAll(userId);

        verify(billingRepo).cancelPendingOrders(userId);
    }

    @Test
    void revokeAll_expiresCreditLots() {
        when(billingRepo.findActiveSubscriptionsForUser(userId)).thenReturn(List.of());
        when(creditLotRepo.expireAllCreditLotsForUser(userId)).thenReturn(3);
        lenient().when(cacheManager.getCache("subscriptionFeatures")).thenReturn(cache);

        service.revokeAll(userId);

        verify(creditLotRepo).expireAllCreditLotsForUser(userId);
    }

    @Test
    void revokeAll_cancelsActiveBoosts() {
        when(billingRepo.findActiveSubscriptionsForUser(userId)).thenReturn(List.of());
        when(creditLotRepo.cancelActiveBoostsForUser(userId)).thenReturn(1);
        lenient().when(cacheManager.getCache("subscriptionFeatures")).thenReturn(cache);

        service.revokeAll(userId);

        verify(creditLotRepo).cancelActiveBoostsForUser(userId);
    }

    @Test
    void revokeAll_cancelsPendingPromotionRedemptions() {
        when(billingRepo.findActiveSubscriptionsForUser(userId)).thenReturn(List.of());
        when(promotionRepo.cancelPendingRedemptionsForUser(userId, "ACCOUNT_DELETED"))
                .thenReturn(1);
        lenient().when(cacheManager.getCache("subscriptionFeatures")).thenReturn(cache);

        service.revokeAll(userId);

        verify(promotionRepo).cancelPendingRedemptionsForUser(userId, "ACCOUNT_DELETED");
    }

    @Test
    void revokeAll_evictsSubscriptionFeaturesCache() {
        when(billingRepo.findActiveSubscriptionsForUser(userId)).thenReturn(List.of());
        when(cacheManager.getCache("subscriptionFeatures")).thenReturn(cache);

        service.revokeAll(userId);

        verify(cache).evict(userId);
    }

    @Test
    void revokeAll_withExternalProvider_cancelsBackendAccessImmediately() {
        when(billingRepo.findActiveSubscriptionsForUser(userId))
                .thenReturn(List.of(sub("APPLE_APP_STORE")));
        when(billingRepo.cancelAllActiveSubscriptions(userId)).thenReturn(1);
        lenient().when(cacheManager.getCache("subscriptionFeatures")).thenReturn(cache);

        service.revokeAll(userId);

        verify(billingRepo).cancelAllActiveSubscriptions(userId);
    }

    @Test
    void revokeAll_withGooglePlayProvider_cancelsBackendAccessImmediately() {
        when(billingRepo.findActiveSubscriptionsForUser(userId))
                .thenReturn(List.of(sub("GOOGLE_PLAY")));
        when(billingRepo.cancelAllActiveSubscriptions(userId)).thenReturn(1);
        lenient().when(cacheManager.getCache("subscriptionFeatures")).thenReturn(cache);

        service.revokeAll(userId);

        verify(billingRepo).cancelAllActiveSubscriptions(userId);
    }

    @Test
    void revokeAll_withRevenueCatProvider_cancelsBackendAccessImmediately() {
        when(billingRepo.findActiveSubscriptionsForUser(userId))
                .thenReturn(List.of(sub("REVENUECAT")));
        when(billingRepo.cancelAllActiveSubscriptions(userId)).thenReturn(1);
        lenient().when(cacheManager.getCache("subscriptionFeatures")).thenReturn(cache);

        service.revokeAll(userId);

        verify(billingRepo).cancelAllActiveSubscriptions(userId);
    }

    @Test
    void revokeAll_idempotent_secondCallStillWorks() {
        when(billingRepo.findActiveSubscriptionsForUser(userId)).thenReturn(List.of());
        when(billingRepo.cancelAllActiveSubscriptions(userId)).thenReturn(0);
        when(billingRepo.cancelPendingOrders(userId)).thenReturn(0);
        when(creditLotRepo.expireAllCreditLotsForUser(userId)).thenReturn(0);
        when(creditLotRepo.cancelActiveBoostsForUser(userId)).thenReturn(0);
        when(promotionRepo.cancelPendingRedemptionsForUser(userId, "ACCOUNT_DELETED"))
                .thenReturn(0);
        lenient().when(cacheManager.getCache("subscriptionFeatures")).thenReturn(cache);

        service.revokeAll(userId);

        verify(billingRepo).cancelAllActiveSubscriptions(userId);
        verify(billingRepo).cancelPendingOrders(userId);
        verify(creditLotRepo).expireAllCreditLotsForUser(userId);
        verify(creditLotRepo).cancelActiveBoostsForUser(userId);
        verify(promotionRepo).cancelPendingRedemptionsForUser(userId, "ACCOUNT_DELETED");
    }

    @Test
    void revokeAll_cacheEvictionFailure_doesNotPropagate() {
        when(billingRepo.findActiveSubscriptionsForUser(userId)).thenReturn(List.of());
        when(cacheManager.getCache("subscriptionFeatures"))
                .thenThrow(new RuntimeException("cache error"));

        service.revokeAll(userId);

        verify(billingRepo).cancelAllActiveSubscriptions(userId);
    }

    @Test
    void revokeAll_noActiveSubscriptions_stillCancelsOrdersAndCredits() {
        when(billingRepo.findActiveSubscriptionsForUser(userId)).thenReturn(List.of());
        lenient().when(cacheManager.getCache("subscriptionFeatures")).thenReturn(cache);

        service.revokeAll(userId);

        verify(billingRepo).cancelPendingOrders(userId);
        verify(creditLotRepo).expireAllCreditLotsForUser(userId);
        verify(creditLotRepo).cancelActiveBoostsForUser(userId);
        verify(promotionRepo).cancelPendingRedemptionsForUser(userId, "ACCOUNT_DELETED");
    }

    @Test
    void revokeAll_doesNotTouchFulfilledRedemptions() {
        when(billingRepo.findActiveSubscriptionsForUser(userId)).thenReturn(List.of());
        when(promotionRepo.cancelPendingRedemptionsForUser(userId, "ACCOUNT_DELETED"))
                .thenReturn(0);
        lenient().when(cacheManager.getCache("subscriptionFeatures")).thenReturn(cache);

        service.revokeAll(userId);

        verify(promotionRepo).cancelPendingRedemptionsForUser(userId, "ACCOUNT_DELETED");
        verify(promotionRepo, never()).cancelRedemption(any(), any(), any());
    }
}
