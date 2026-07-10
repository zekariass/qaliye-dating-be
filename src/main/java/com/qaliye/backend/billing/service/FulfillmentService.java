package com.qaliye.backend.billing.service;

import com.qaliye.backend.billing.repository.BillingRepository;
import com.qaliye.backend.billing.repository.CreditLotRepository;
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

    public FulfillmentService(BillingRepository billingRepo, CreditLotRepository creditLotRepo) {
        this.billingRepo = billingRepo;
        this.creditLotRepo = creditLotRepo;
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

        // Grant monthly boost allowance for PREMIUM
        grantMonthlyBoostAllowance(userId, subId, offer, periodEnd);

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
        Instant expiresAt = offer.expiresAfterDays() != null
                ? Instant.now().plus(offer.expiresAfterDays(), ChronoUnit.DAYS)
                : null;

        String idempotencyKey = "order-" + order.id();
        UUID ledgerEntryId = creditLotRepo.insertLedgerEntry(
                userId, offer.entitlementType(), offer.quantityGranted(),
                "PURCHASE", transactionId, null, null,
                idempotencyKey, expiresAt, "{}"
        );

        if (ledgerEntryId != null) {
            creditLotRepo.createLot(userId, offer.entitlementType(), ledgerEntryId,
                    offer.quantityGranted(), expiresAt);
        }

        log.info("Consumable fulfilled: user={}, type={}, qty={}", userId, offer.entitlementType(), offer.quantityGranted());
    }

    private void grantMonthlyBoostAllowance(UUID userId, UUID subscriptionId, BillingRepository.FullOfferRow offer,
                                               Instant periodEnd) {
        if (offer.planId() == null) return;

        int boostQty = creditLotRepo.getPlanBoostLimit(offer.planId());
        if (boostQty <= 0) return;

        String idempotencyKey = "sub-boost-" + subscriptionId + "-" + Instant.now().truncatedTo(ChronoUnit.DAYS);

        UUID ledgerEntryId = creditLotRepo.insertLedgerEntry(
                userId, "BOOST_CREDIT", boostQty, "SUBSCRIPTION_ALLOWANCE",
                null, subscriptionId, null,
                idempotencyKey, periodEnd, "{\"reason\":\"monthly_allowance\"}"
        );

        if (ledgerEntryId != null) {
            creditLotRepo.createLot(userId, "BOOST_CREDIT", ledgerEntryId, boostQty, periodEnd);
            log.info("Monthly boost allowance granted: user={}, qty={}, expiresAt={}", userId, boostQty, periodEnd);
        }
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

        // ── Step 6: Grant monthly boost allowance (idempotent by stable sub ID + period day) ──
        int boostQty = creditLotRepo.getPlanBoostLimit(planId);
        if (boostQty > 0) {
            String idempotencyKey = "rc-boost-" + stableSubId + "-" + periodStart.truncatedTo(ChronoUnit.DAYS);
            UUID ledgerEntryId = creditLotRepo.insertLedgerEntry(
                    userId, "BOOST_CREDIT", boostQty, "SUBSCRIPTION_ALLOWANCE",
                    null, subId, null, idempotencyKey, periodEnd,
                    "{\"reason\":\"revenuecat_subscription_allowance\"}"
            );
            if (ledgerEntryId != null) {
                creditLotRepo.createLot(userId, "BOOST_CREDIT", ledgerEntryId, boostQty, periodEnd);
            }
        }

        log.info("RevenueCat subscription fulfilled: user={}, stableSubId={}, ref={}, periodEnd={}",
                userId, stableSubId, providerSubRef, periodEnd);

        return subId;
    }

    public void fulfillRevenueCatConsumable(UUID userId, String entitlementType,
                                            int quantity, UUID transactionId,
                                            String providerTransactionId, Integer expiresAfterDays) {
        Instant expiresAt = expiresAfterDays != null
                ? Instant.now().plus(expiresAfterDays, ChronoUnit.DAYS)
                : null;

        String idempotencyKey = "rc-" + providerTransactionId;
        UUID ledgerEntryId = creditLotRepo.insertLedgerEntry(
                userId, entitlementType, quantity, "PURCHASE",
                transactionId, null, null, idempotencyKey, expiresAt, "{}"
        );
        if (ledgerEntryId != null) {
            creditLotRepo.createLot(userId, entitlementType, ledgerEntryId, quantity, expiresAt);
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
