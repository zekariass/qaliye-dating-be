package com.qaliye.backend.billing.service;

import com.qaliye.backend.billing.repository.BillingRepository;
import com.qaliye.backend.billing.repository.CreditLotRepository;
import com.qaliye.backend.billing.repository.PromotionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class FulfillmentService {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentService.class);

    private final BillingRepository billingRepo;
    private final CreditLotRepository creditLotRepo;
    private final CreditService creditService;
    private final PromotionRepository promotionRepo;

    public FulfillmentService(BillingRepository billingRepo, CreditLotRepository creditLotRepo,
                               CreditService creditService, PromotionRepository promotionRepo) {
        this.billingRepo = billingRepo;
        this.creditLotRepo = creditLotRepo;
        this.creditService = creditService;
        this.promotionRepo = promotionRepo;
    }

    @Transactional
    @CacheEvict(value = "subscriptionFeatures", key = "#userId")
    public void fulfillVerifiedOrder(UUID orderId, UUID userId) {
        BillingRepository.OrderRow order = billingRepo.findOrderById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));

        if (!"VERIFIED".equals(order.status())) {
            log.warn("Attempted to fulfill non-verified order={}, status={}", orderId, order.status());
            return;
        }

        BillingRepository.FullOfferRow offer = billingRepo.findOfferById(order.paymentOfferId())
                .orElseThrow(() -> new IllegalStateException("Offer not found for order: " + orderId));

        if (offer.subscriptionProductId() != null) {
            fulfillSubscription(order, offer, userId);
        } else if (offer.consumableProductId() != null) {
            fulfillConsumable(order, offer, userId);
        }
    }

    private void fulfillSubscription(BillingRepository.OrderRow order, BillingRepository.FullOfferRow offer, UUID userId) {
        Instant now = Instant.now();
        Instant periodEnd = calculatePeriodEnd(now, offer.billingIntervalUnit(), offer.billingIntervalCount());

        // Extend periodEnd with remaining days from an active PROMOTION subscription
        Optional<BillingRepository.ActiveSubRow> activeSub = billingRepo.findActiveSubscription(userId);
        if (activeSub.isPresent() && "PROMOTION".equals(activeSub.get().provider())) {
            Instant promoEnd = activeSub.get().periodEnd();
            if (promoEnd.isAfter(now)) {
                long remainingSeconds = promoEnd.getEpochSecond() - now.getEpochSecond();
                periodEnd = periodEnd.plus(remainingSeconds, ChronoUnit.SECONDS);
                log.info("Extending paid subscription with {} remaining promotion seconds for user={}",
                        remainingSeconds, userId);
            }
        }

        String provider = mapMethodCodeToProvider(order.methodCode());

        // Create transaction
        UUID transactionId = billingRepo.insertTransaction(
                userId, null, order.id(), offer.id(), null,
                "SUBSCRIPTION", "PURCHASE",
                order.expectedAmountMinorUnits(), order.expectedCurrency(),
                provider, order.orderReference(), null,
                offer.countryCode(), "COMPLETED"
        );

        // Upsert subscription
        UUID subId = billingRepo.upsertSubscription(
                userId, offer.planId(), provider,
                order.orderReference(), offer.id(),
                null, "ACTIVE", offer.autoRenew(),
                now, now, periodEnd
        );

        // Expire any remaining subscription allowance credits from the previous period
        String expireIdemKey = "sub-expire-" + subId + "-" + periodEnd.truncatedTo(ChronoUnit.DAYS);
        creditService.expireSubscriptionAllowanceLots(userId, subId, expireIdemKey);

        // Grant included subscription credits into central credit balance
        grantSubscriptionIncludedCredits(userId, subId, offer, periodEnd);

        // Fulfill any associated PURCHASE promotion redemption
        try {
            promotionRepo.fulfillPurchaseRedemptionByOrderId(order.id(), subId);
        } catch (Exception e) {
            log.error("Failed to fulfill promotion redemption for order={}: {}", order.id(), e.getMessage());
        }

        log.info("Subscription fulfilled: user={}, plan={}, periodEnd={}", userId, offer.subProductCode(), periodEnd);
    }

    private void fulfillConsumable(BillingRepository.OrderRow order, BillingRepository.FullOfferRow offer, UUID userId) {
        String provider = mapMethodCodeToProvider(order.methodCode());

        // Create transaction
        UUID transactionId = billingRepo.insertTransaction(
                userId, null, order.id(), offer.id(), null,
                "CONSUMABLE", "PURCHASE",
                order.expectedAmountMinorUnits(), order.expectedCurrency(),
                provider, order.orderReference(), null,
                offer.countryCode(), "COMPLETED"
        );

        // Grant credits
        String idempotencyKey = "order-" + order.id();

        if ("CREDIT_PURCHASE".equals(offer.entitlementType())) {
            long qty = offer.quantityGranted() != null ? offer.quantityGranted() : 0L;
            creditService.grantPurchasedCredits(userId, qty, transactionId, idempotencyKey);
        } else {
            Instant expiresAt = offer.expiresAfterDays() != null
                    ? Instant.now().plus(offer.expiresAfterDays(), ChronoUnit.DAYS)
                    : null;
            UUID ledgerEntryId = creditLotRepo.insertLedgerEntry(
                    userId, offer.entitlementType(), offer.quantityGranted() != null ? offer.quantityGranted() : 0,
                    "PURCHASE", transactionId, null, null,
                    idempotencyKey, expiresAt, "{}"
            );
            if (ledgerEntryId != null) {
                creditLotRepo.createLot(userId, offer.entitlementType(), ledgerEntryId,
                        offer.quantityGranted() != null ? offer.quantityGranted() : 0, expiresAt);
            }
        }

        log.info("Consumable fulfilled: user={}, type={}, qty={}", userId, offer.entitlementType(), offer.quantityGranted());

        // Fulfill any associated PURCHASE promotion redemption
        try {
            promotionRepo.fulfillPurchaseRedemptionByOrderId(order.id(), null);
        } catch (Exception e) {
            log.error("Failed to fulfill promotion redemption for consumable order={}: {}", order.id(), e.getMessage());
        }
    }

    private void grantSubscriptionIncludedCredits(UUID userId, UUID subscriptionId,
                                                    BillingRepository.FullOfferRow offer, Instant periodEnd) {
        if (offer.includedCredits() <= 0) return;
        String idemKey = "sub-credits-" + subscriptionId + "-" + periodEnd.truncatedTo(ChronoUnit.DAYS);
        creditService.grantSubscriptionAllowance(userId, offer.includedCredits(), subscriptionId, idemKey, periodEnd);
        log.info("Subscription credits granted: user={}, credits={}, expiresAt={}", userId, offer.includedCredits(), periodEnd);
    }

    @Transactional
    public UUID fulfillRevenueCatSubscription(UUID userId, String stableSubId, String providerSubRef,
                                              UUID planId, UUID offerId,
                                              Instant periodStart, Instant periodEnd,
                                              String provider, boolean autoRenew,
                                              String transactionType) {
        if (stableSubId == null) {
            throw new IllegalArgumentException("stableSubId must not be null");
        }

        // ── Step 1: Lock the user row to serialize all subscription ops for this user ──
        // This prevents two concurrent webhooks from both proceeding when no subscription
        // rows exist yet (SELECT ... FOR UPDATE on empty set doesn't block).
        billingRepo.lockUserRowForUpdate(userId);

        // ── Step 2: Lock ALL subscription rows for this user ──
        List<BillingRepository.SubscriptionRow> allSubs =
                billingRepo.lockAllSubscriptionsForUpdate(userId);

        // ── Step 3: Find matching row by provider + stable provider_subscription_id ──
        BillingRepository.SubscriptionRow matching = allSubs.stream()
                .filter(s -> provider.equals(s.provider()) && stableSubId.equals(s.providerSubscriptionId()))
                .findFirst()
                .orElse(null);

        // ── Step 4: Find currently active row ──
        BillingRepository.SubscriptionRow active = allSubs.stream()
                .filter(s -> "ACTIVE".equals(s.status()) || "PENDING_VERIFICATION".equals(s.status()))
                .findFirst()
                .orElse(null);

        UUID subId;

        if (matching != null) {
            // ── Case A: Matching row exists → update that row only ──
            if (active != null && !active.id().equals(matching.id())) {
                billingRepo.markSubscriptionReplaced(active.id());
                log.info("RevenueCat active subscription replaced: user={}, oldSubId={}, oldStableSubId={}",
                        userId, active.id(), active.providerSubscriptionId());
            }
            billingRepo.updateSubscriptionById(matching.id(), planId, provider, stableSubId, offerId,
                    providerSubRef, "ACTIVE", autoRenew, periodStart, periodEnd);
            subId = matching.id();
            log.info("RevenueCat subscription updated: user={}, subId={}, stableSubId={}, ref={}",
                    userId, subId, stableSubId, providerSubRef);
        } else if (active == null) {
            // ── Case B: No matching row and no active row → insert new ACTIVE ──
            subId = billingRepo.insertSubscription(userId, planId, provider, stableSubId, offerId,
                    providerSubRef, "ACTIVE", autoRenew, periodStart, periodStart, periodEnd);
            log.info("RevenueCat subscription inserted: user={}, subId={}, stableSubId={}, ref={}",
                    userId, subId, stableSubId, providerSubRef);
        } else if (stableSubId.equals(active.providerSubscriptionId())
                    && provider.equals(active.provider())) {
            // ── Case C: No matching row but active row has same stable ID → repair/update ──
            billingRepo.updateSubscriptionById(active.id(), planId, provider, stableSubId, offerId,
                    providerSubRef, "ACTIVE", autoRenew, periodStart, periodEnd);
            subId = active.id();
            log.info("RevenueCat subscription updated (repaired): user={}, subId={}, stableSubId={}",
                    userId, subId, stableSubId);
        } else {
            // ── Case D: No matching row, active row has different stable ID → replacement ──
            // Deactivate old ACTIVE first, then insert new
            billingRepo.markSubscriptionReplaced(active.id());
            log.info("RevenueCat old subscription replaced: user={}, oldSubId={}, oldStableSubId={}",
                    userId, active.id(), active.providerSubscriptionId());
            subId = billingRepo.insertSubscription(userId, planId, provider, stableSubId, offerId,
                    providerSubRef, "ACTIVE", autoRenew, periodStart, periodStart, periodEnd);
            log.info("RevenueCat replacement subscription inserted: user={}, subId={}, stableSubId={}",
                    userId, subId, stableSubId);
        }

        // ── Step 5: Create transaction idempotently ──
        if (providerSubRef != null && !providerSubRef.isBlank()) {
            Optional<UUID> existingTx = billingRepo.findTransactionByProviderTxId(provider, providerSubRef);
            if (existingTx.isEmpty()) {
                billingRepo.insertTransaction(userId, subId, null, offerId, null,
                        "SUBSCRIPTION", transactionType != null ? transactionType : "PURCHASE",
                        0, "USD", provider, providerSubRef,
                        null, null, "COMPLETED");
            }
        }

        // ── Step 6: Expire previous period allowance credits before granting new ones ──
        final UUID finalSubId = subId;
        String expireIdemKey = "rc-expire-" + stableSubId + "-" + periodStart.truncatedTo(ChronoUnit.DAYS);
        creditService.expireSubscriptionAllowanceLots(userId, finalSubId, expireIdemKey);

        // ── Step 7: Grant included subscription credits (idempotent) ──
        billingRepo.findOfferById(offerId).ifPresent(offer -> {
            if (offer.includedCredits() > 0) {
                String credIdemKey = "rc-credits-" + stableSubId + "-" + periodStart.truncatedTo(ChronoUnit.DAYS);
                creditService.grantSubscriptionAllowance(userId, offer.includedCredits(), finalSubId, credIdemKey, periodEnd);
            }
        });

        log.info("RevenueCat subscription fulfilled: user={}, stableSubId={}, ref={}, periodEnd={}",
                userId, stableSubId, providerSubRef, periodEnd);

        return subId;
    }

    public void fulfillRevenueCatConsumable(UUID userId, String entitlementType,
                                            int quantity, UUID transactionId,
                                            String providerTransactionId, Integer expiresAfterDays) {
        String idempotencyKey = "rc-" + providerTransactionId;

        if ("CREDIT_PURCHASE".equals(entitlementType)) {
            creditService.grantPurchasedCredits(userId, quantity, transactionId, idempotencyKey);
        } else {
            Instant expiresAt = expiresAfterDays != null
                    ? Instant.now().plus(expiresAfterDays, ChronoUnit.DAYS)
                    : null;
            UUID ledgerEntryId = creditLotRepo.insertLedgerEntry(
                    userId, entitlementType, quantity, "PURCHASE",
                    transactionId, null, null, idempotencyKey, expiresAt, "{}"
            );
            if (ledgerEntryId != null) {
                creditLotRepo.createLot(userId, entitlementType, ledgerEntryId, quantity, expiresAt);
            }
        }

        log.info("RevenueCat consumable fulfilled: user={}, type={}, qty={}", userId, entitlementType, quantity);
    }

    private Instant calculatePeriodEnd(Instant start, String intervalUnit, Integer count) {
        if (intervalUnit == null || count == null) {
            return start.plus(30, ChronoUnit.DAYS);
        }
        return switch (intervalUnit) {
            case "DAY" -> start.plus(count, ChronoUnit.DAYS);
            case "WEEK" -> start.plus((long) count * 7, ChronoUnit.DAYS);
            case "MONTH" -> start.plus((long) count * 30, ChronoUnit.DAYS);
            case "YEAR" -> start.plus((long) count * 365, ChronoUnit.DAYS);
            default -> start.plus(30, ChronoUnit.DAYS);
        };
    }

    private String mapMethodCodeToProvider(String methodCode) {
        if (methodCode == null) return "BANK_TRANSFER";
        return switch (methodCode) {
            case "chapa" -> "CHAPA";
            case "arifpay" -> "ARIFPAY";
            case "telebirr" -> "TELEBIRR";
            case "cbebirr" -> "CBE_BIRR";
            case "apple" -> "APPLE_APP_STORE";
            case "google" -> "GOOGLE_PLAY";
            case "stripe" -> "STRIPE";
            default -> "BANK_TRANSFER";
        };
    }
}
