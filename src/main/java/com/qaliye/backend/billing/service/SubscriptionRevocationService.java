package com.qaliye.backend.billing.service;

import com.qaliye.backend.billing.repository.BillingRepository;
import com.qaliye.backend.billing.repository.CreditLotRepository;
import com.qaliye.backend.billing.repository.PromotionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Revokes all active subscriptions, entitlements, pending orders, and pending promotion
 * reservations for a user. Designed to be called within the account-deletion transaction.
 *
 * <p>Idempotent: all UPDATE statements use WHERE clauses that filter on active/pending
 * statuses, so a second call after the first is a no-op (0 rows affected).
 *
 * <p>For externally managed subscriptions (Apple App Store, Google Play via RevenueCat),
 * the backend access is revoked immediately. The external provider will eventually send
 * a CANCELLATION or EXPIRATION webhook confirming the platform-side cancellation.
 */
@Service
public class SubscriptionRevocationService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionRevocationService.class);

    private static final Set<String> EXTERNAL_PROVIDERS = Set.of(
            "APPLE_APP_STORE", "GOOGLE_PLAY", "REVENUECAT"
    );

    private final BillingRepository billingRepo;
    private final CreditLotRepository creditLotRepo;
    private final PromotionRepository promotionRepo;
    private final CacheManager cacheManager;

    public SubscriptionRevocationService(BillingRepository billingRepo,
                                          CreditLotRepository creditLotRepo,
                                          PromotionRepository promotionRepo,
                                          CacheManager cacheManager) {
        this.billingRepo = billingRepo;
        this.creditLotRepo = creditLotRepo;
        this.promotionRepo = promotionRepo;
        this.cacheManager = cacheManager;
    }

    /**
     * Revokes all active subscriptions, entitlements, pending orders, and pending
     * promotion reservations for the given user. Must be called within an existing
     * transaction.
     */
    public void revokeAll(UUID userId) {
        List<BillingRepository.ActiveSubscriptionRow> activeSubs =
                billingRepo.findActiveSubscriptionsForUser(userId);

        int subsCancelled = billingRepo.cancelAllActiveSubscriptions(userId);
        log.info("Cancelled {} active subscription(s) for user {}", subsCancelled, userId);

        for (BillingRepository.ActiveSubscriptionRow sub : activeSubs) {
            if (EXTERNAL_PROVIDERS.contains(sub.provider())) {
                log.info("Subscription {} uses external provider {} – backend access revoked; "
                                + "provider-side cancellation will be confirmed via webhook",
                        sub.id(), sub.provider());
            }
        }

        int ordersCancelled = billingRepo.cancelPendingOrders(userId);
        log.info("Cancelled {} pending payment order(s) for user {}", ordersCancelled, userId);

        int lotsExpired = creditLotRepo.expireAllCreditLotsForUser(userId);
        log.info("Expired {} credit lot(s) for user {}", lotsExpired, userId);

        int boostsCancelled = creditLotRepo.cancelActiveBoostsForUser(userId);
        log.info("Cancelled {} active boost(s) for user {}", boostsCancelled, userId);

        int redemptionsCancelled = promotionRepo.cancelPendingRedemptionsForUser(
                userId, "ACCOUNT_DELETED");
        log.info("Cancelled {} pending promotion redemption(s) for user {}",
                redemptionsCancelled, userId);

        evictSubscriptionCache(userId);
    }

    private void evictSubscriptionCache(UUID userId) {
        try {
            var cache = cacheManager.getCache("subscriptionFeatures");
            if (cache != null) {
                cache.evict(userId);
            }
        } catch (Exception e) {
            log.warn("Failed to evict subscriptionFeatures cache for {}: {}", userId, e.getMessage());
        }
    }
}
