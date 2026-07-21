package com.qaliye.backend.billing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaliye.backend.billing.BillingProperties;
import com.qaliye.backend.billing.dto.CreateOrderRequest;
import com.qaliye.backend.billing.dto.ManualReceiptRequest;
import com.qaliye.backend.billing.dto.ManualTransferVerifyRequest;
import com.qaliye.backend.billing.dto.OrderListResponse;
import com.qaliye.backend.billing.dto.OrderResponse;
import com.qaliye.backend.billing.dto.OrderSummaryDto;
import com.qaliye.backend.billing.provider.LocalGatewayRegistry;
import com.qaliye.backend.billing.provider.LocalOnlinePaymentGateway;
import com.qaliye.backend.billing.provider.VerifyEtClient;
import com.qaliye.backend.billing.repository.BillingRepository;
import com.qaliye.backend.billing.repository.PromotionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final DateTimeFormatter EXPIRY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);
    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> VALID_STATUSES = Set.of(
            "CREATED", "AWAITING_PAYMENT", "RECEIPT_SUBMITTED", "REVIEW_REQUIRED",
            "VERIFICATION_PENDING", "MANUAL_REVIEW",
            "VERIFIED", "REJECTED", "EXPIRED", "CANCELLED"
    );

    private final BillingRepository billingRepo;
    private final BillingProperties billingProps;
    private final BillingMarketResolver marketResolver;
    private final LocalGatewayRegistry gatewayRegistry;
    private final VerifyEtClient verifyEtClient;
    private final FulfillmentService fulfillmentService;
    private final ObjectMapper objectMapper;
    private final PromotionRepository promotionRepo;
    private final PromotionEligibilityService promotionEligibilityService;

    public OrderService(BillingRepository billingRepo,
                        BillingProperties billingProps,
                        BillingMarketResolver marketResolver,
                        LocalGatewayRegistry gatewayRegistry,
                        VerifyEtClient verifyEtClient,
                        FulfillmentService fulfillmentService,
                        ObjectMapper objectMapper,
                        PromotionRepository promotionRepo,
                        PromotionEligibilityService promotionEligibilityService) {
        this.billingRepo = billingRepo;
        this.billingProps = billingProps;
        this.marketResolver = marketResolver;
        this.gatewayRegistry = gatewayRegistry;
        this.verifyEtClient = verifyEtClient;
        this.fulfillmentService = fulfillmentService;
        this.objectMapper = objectMapper;
        this.promotionRepo = promotionRepo;
        this.promotionEligibilityService = promotionEligibilityService;
    }

    @Transactional
    public OrderResponse createOrder(UUID userId, CreateOrderRequest request) {
        // Idempotency check
        if (request.idempotencyKey() != null) {
            Optional<BillingRepository.OrderRow> existing =
                    billingRepo.findOrderByIdempotency(userId, request.idempotencyKey());
            if (existing.isPresent()) {
                return toOrderResponse(existing.get());
            }
        }

        // Validate offer
        BillingRepository.FullOfferRow offer = billingRepo.findOfferById(request.paymentOfferId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_offer"));

        // Resolve the user's billing market
        String platform = request.platform() != null ? request.platform().toUpperCase() : "ANDROID";
        BillingMarketResolver.MarketResult market = marketResolver.resolveMarket(userId, platform);

        // For ONLINE_PAYMENT: validate the submitted method is the single active gateway method
        BillingRepository.PaymentMethodRow activeOnlineMethod =
                billingRepo.findActiveOnlinePaymentMethod(market.resolvedCountryCode(), market.platform())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "no_active_online_payment_method"));

        if (!activeOnlineMethod.id().equals(request.paymentMethodId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_payment_method_for_market");
        }

        BillingRepository.PaymentMethodRow method = activeOnlineMethod;

        if (!method.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "payment_method_unavailable");
        }

        // Validate offer and method belong to the same market
        validateMarketMatch(offer, method);

        // Resolve and validate gateway
        LocalOnlinePaymentGateway gateway = gatewayRegistry.resolve(method.methodCode());

        // Promotion: find best PURCHASE discount for this offer
        String trustedCountry = "ET".equalsIgnoreCase(market.billingCountryCode()) ? "ET" : "GLOBAL";
        PromotionEligibilityService.AppliedPromotion appliedPromotion = null;
        int effectiveAmount = offer.priceMinorUnits();

        if (offer.subscriptionProductId() != null) {
            Optional<PromotionEligibilityService.AppliedPromotion> promoOpt =
                    promotionEligibilityService.findBestPurchasePromotion(
                            userId, offer.subscriptionProductId(),
                            offer.priceMinorUnits(), offer.currency(), trustedCountry);
            if (promoOpt.isPresent()) {
                var promo = promoOpt.get();
                boolean reserved = promotionRepo.atomicReserveCapacity(
                        promo.campaign().id(), userId, promo.campaign().maxRedemptionsPerUser());
                if (reserved) {
                    effectiveAmount = (int) promo.discount().finalAmountMinor();
                    appliedPromotion = promo;
                    log.info("Promotion applied: user={} campaign={} saving={}",
                            userId, promo.campaign().campaignKey(),
                            promo.discount().discountAmountMinor());
                }
            }
        }

        String orderReference = generateOrderReference();
        Instant expiresAt = Instant.now().plus(billingProps.getPaymentOrderExpiryHours(), ChronoUnit.HOURS);
        String instructionSnapshot = buildOnlinePaymentSnapshot(method, offer, orderReference, expiresAt);

        String initialStatus = "AWAITING_PAYMENT";
        String checkoutUrl = null;
        String providerRef = null;

        try {
            LocalOnlinePaymentGateway.CheckoutResult checkout = gateway.createCheckout(
                    orderReference,
                    effectiveAmount,
                    offer.currency(),
                    userId.toString()
            );
            checkoutUrl = checkout.checkoutUrl();
            providerRef = checkout.txRef();
        } catch (ResponseStatusException e) {
            if (appliedPromotion != null) {
                promotionRepo.releaseReservation(appliedPromotion.campaign().id());
            }
            throw e;
        } catch (Exception e) {
            log.error("Gateway checkout creation failed for user={} method={}: {}",
                    userId, method.methodCode(), e.getMessage());
            initialStatus = "CREATED";
        }

        BillingRepository.OrderRow order = billingRepo.insertOrder(
                userId, offer.id(), method.id(),
                orderReference, initialStatus,
                effectiveAmount, offer.currency(),
                instructionSnapshot, checkoutUrl, expiresAt,
                request.idempotencyKey()
        );

        if (checkoutUrl != null && "AWAITING_PAYMENT".equals(initialStatus)) {
            billingRepo.updateOrderWithCheckout(order.id(), "AWAITING_PAYMENT", checkoutUrl, providerRef);
            order = billingRepo.findOrderById(order.id()).orElse(order);
        }

        // Persist promotion redemption after order is created
        if (appliedPromotion != null) {
            try {
                String userGender = promotionRepo.getUserGender(userId).orElse(null);
                promotionRepo.insertRedemption(
                        appliedPromotion.campaign().id(), userId, offer.id(), order.id(),
                        "RESERVED", trustedCountry, userGender,
                        offer.priceMinorUnits(),
                        appliedPromotion.discount().discountAmountMinor(),
                        effectiveAmount, offer.currency());
            } catch (Exception e) {
                log.error("Failed to insert promotion redemption for order={}: {}", order.id(), e.getMessage());
                promotionRepo.releaseReservation(appliedPromotion.campaign().id());
            }
        }

        return toOrderResponse(order, instructionSnapshot);
    }

    public OrderListResponse listOrders(UUID userId, List<String> statuses, int page, int pageSize) {
        if (statuses != null) {
            statuses = statuses.stream()
                    .filter(VALID_STATUSES::contains)
                    .toList();
            if (statuses.isEmpty()) {
                statuses = null;
            }
        }

        int safePage = Math.max(1, page);
        int safePageSize = Math.min(MAX_PAGE_SIZE, Math.max(1, pageSize));
        int offset = (safePage - 1) * safePageSize;

        List<BillingRepository.OrderSummaryRow> rows =
                billingRepo.findOrderSummariesByUserId(userId, statuses, safePageSize, offset);
        long total = billingRepo.countOrdersByUserId(userId, statuses);
        int totalPages = safePageSize == 0 ? 0 : (int) Math.ceil((double) total / safePageSize);

        List<OrderSummaryDto> dtos = rows.stream()
                .map(this::toOrderSummaryDto)
                .collect(Collectors.toList());

        return new OrderListResponse(dtos, safePage, safePageSize, total, totalPages);
    }

    public OrderResponse getOrder(UUID userId, UUID orderId) {
        BillingRepository.OrderRow order = billingRepo.findOrderById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "order_not_found"));

        if (!order.userId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "access_denied");
        }
        return toOrderResponse(order);
    }

    @Transactional
    public OrderResponse submitManualTransferVerification(UUID userId, ManualTransferVerifyRequest request) {
        // Idempotency check
        if (request.idempotencyKey() != null) {
            Optional<BillingRepository.OrderRow> existing =
                    billingRepo.findOrderByIdempotency(userId, request.idempotencyKey());
            if (existing.isPresent()) {
                return toOrderResponse(existing.get());
            }
        }

        // Validate offer – amounts come from the offer, never from user input
        BillingRepository.FullOfferRow offer = billingRepo.findOfferById(request.paymentOfferId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_offer"));

        // Validate payment method
        BillingRepository.PaymentMethodRow method = billingRepo.findPaymentMethodById(request.paymentMethodId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_payment_method"));

        if (!method.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "payment_method_unavailable");
        }
        if (!"MANUAL_TRANSFER".equals(method.paymentChannel())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "not_manual_transfer_method");
        }

        validateMarketMatch(offer, method);

        // Dedup: extract and normalize the manual payment reference
        String methodCode = method.methodCode();
        String manualRef = extractTransactionRef(methodCode, request.verificationData());
        String normalizedRef = manualRef != null ? manualRef.trim().toUpperCase(Locale.ROOT) : null;

        // Check for an existing active order with the same manual reference
        if (normalizedRef != null) {
            Optional<BillingRepository.OrderRow> existingByRef =
                    billingRepo.findOrderByManualReference(method.id(), normalizedRef);
            if (existingByRef.isPresent()) {
                BillingRepository.OrderRow existingOrder = existingByRef.get();
                log.info("verify.et dedup: found existing order={} status={} for ref={}",
                        existingOrder.id(), existingOrder.status(), normalizedRef);

                // If still VERIFICATION_PENDING with a queued request ID, treat as a status poll
                if ("VERIFICATION_PENDING".equals(existingOrder.status())
                        && existingOrder.providerVerificationRequestId() != null) {
                    return pollAndUpdateOrder(existingOrder, offer, method);
                }

                // New frontend verify request for an existing reference: count it.
                // We do not increment when polling an already-queued request above.
                billingRepo.incrementVerificationCount(existingOrder.id());
                BillingRepository.OrderRow refreshedOrder =
                        billingRepo.findOrderById(existingOrder.id()).orElse(existingOrder);
                return toOrderResponse(refreshedOrder);
            }
        }

        // Create order in VERIFICATION_PENDING; expected amounts come from the offer
        String orderReference = generateOrderReference();
        Instant expiresAt = Instant.now().plus(billingProps.getPaymentOrderExpiryHours(), ChronoUnit.HOURS);
        String instructionSnapshot = buildManualTransferSnapshot(method, request.verificationData());

        BillingRepository.OrderRow order = billingRepo.insertManualTransferOrder(
                userId, offer.id(), method.id(),
                orderReference, "VERIFICATION_PENDING",
                offer.priceMinorUnits(), offer.currency(),
                instructionSnapshot, expiresAt,
                request.idempotencyKey(),
                manualRef, normalizedRef
        );

        UUID orderId = order.id();

        // Increment per-frontend verify request for the newly created order.
        // Idempotent retries and queued-order polling above are intentionally excluded.
        billingRepo.incrementVerificationCount(orderId);
        String idempotencyKey = "verify-et-" + orderId;
        String webhookUrl = billingProps.getVerifier().getWebhookUrl();

        UUID proofId = billingRepo.insertProof(orderId, "TRANSACTION_REFERENCE",
                methodCode, manualRef, null, null,
                offer.priceMinorUnits(), offer.currency());

        UUID verificationId = billingRepo.insertVerificationAttempt(orderId, proofId,
                "VERIFY_ET", "PENDING", "{}");

        try {
            VerifyEtClient.VerifyEtResponse resp = verifyEtClient.submit(
                    methodCode, request.verificationData(), idempotencyKey, webhookUrl);

            String rawResponse = toJson(resp);

            if (resp.queued()) {
                billingRepo.updateOrderProviderVerificationRequestId(orderId, resp.requestId());
                billingRepo.updateVerificationAttemptWithVerifyEtRequest(
                        verificationId, resp.requestId(), idempotencyKey, "PENDING", rawResponse);
                log.info("verify.et queued: order={}, requestId={}", orderId, resp.requestId());
            } else {
                applyVerifyEtResult(orderId, userId, verificationId, idempotencyKey,
                        offer.priceMinorUnits(), offer.currency(), methodCode, resp, rawResponse);
            }
        } catch (VerifyEtClient.VerifyEtException e) {
            log.warn("verify.et submission failed for order={}: {}", orderId, e.getMessage());
            billingRepo.updateOrderStatus(orderId, "MANUAL_REVIEW",
                    "verify.et submission failed: " + e.getMessage());
        }

        order = billingRepo.findOrderById(orderId).orElse(order);
        return toOrderResponse(order, instructionSnapshot);
    }

    private OrderResponse pollAndUpdateOrder(BillingRepository.OrderRow order,
                                             BillingRepository.FullOfferRow offer,
                                             BillingRepository.PaymentMethodRow method) {
        String requestId = order.providerVerificationRequestId();
        try {
            VerifyEtClient.VerifyEtResponse resp = verifyEtClient.checkStatus(requestId);
            if (resp.queued()) {
                log.info("verify.et poll: still queued, requestId={}, order={}", requestId, order.id());
                return toOrderResponse(order);
            }
            // Find the existing verification attempt to finalize it
            Optional<BillingRepository.VerificationAttemptRow> attemptOpt =
                    billingRepo.findVerificationByVerifyEtRequestId(requestId);
            String idempotencyKey = "verify-et-" + order.id();
            String rawResponse = toJson(resp);

            if (attemptOpt.isPresent()) {
                applyVerifyEtResult(order.id(), order.userId(), attemptOpt.get().id(),
                        idempotencyKey, offer.priceMinorUnits(), offer.currency(),
                        method.methodCode(), resp, rawResponse);
            } else {
                StatusResult statusResult = resolveInlineStatus(resp,
                        offer.priceMinorUnits(), offer.currency(), method.methodCode());
                billingRepo.updateOrderStatus(order.id(), statusResult.status(), statusResult.reason());
                if ("VERIFIED".equals(statusResult.status())) {
                    fulfillmentService.fulfillVerifiedOrder(order.id(), order.userId());
                }
            }
            return toOrderResponse(billingRepo.findOrderById(order.id()).orElse(order));
        } catch (VerifyEtClient.VerifyEtException e) {
            log.warn("verify.et poll failed for order={}: {}", order.id(), e.getMessage());
            return toOrderResponse(order);
        }
    }

    private void applyVerifyEtResult(UUID orderId, UUID userId, UUID verificationId,
                                     String idempotencyKey,
                                     int expectedAmountMinorUnits, String expectedCurrency,
                                     String methodCode,
                                     VerifyEtClient.VerifyEtResponse resp, String rawResponse) {
        StatusResult statusResult = resolveInlineStatus(resp,
                expectedAmountMinorUnits, expectedCurrency, methodCode);
        String orderStatus = statusResult.status();
        Integer verifiedAmount = parseVerifyEtAmount(resp);
        String verifiedCurrency = resp.result() != null ? resp.result().currency() : null;
        String providerRef = resp.result() != null ? resp.result().referenceNumber() : null;
        Boolean settlementMatched = resp.result() != null && resp.result().settlementAccountMatch() != null
                ? resp.result().settlementAccountMatch().matched() : null;
        Boolean confirmedBefore = resp.result() != null && resp.result().confirmationHistory() != null
                && resp.result().confirmationHistory().confirmedBefore();

        // Duplicate provider reference check
        if (providerRef != null
                && billingRepo.existsVerifiedProviderReference(providerRef, orderId)) {
            log.warn("verify.et: duplicate providerRef={} for order={}", providerRef, orderId);
            statusResult = new StatusResult("MANUAL_REVIEW", "duplicate provider reference: " + providerRef);
            orderStatus = "MANUAL_REVIEW";
        }

        billingRepo.updateVerificationAttemptWithVerifyEtRequest(
                verificationId, resp.requestId(), idempotencyKey, orderStatus, rawResponse);
        billingRepo.finalizeVerificationAttemptVerifyEt(
                verificationId, orderStatus, verifiedAmount, verifiedCurrency,
                providerRef, settlementMatched, confirmedBefore, rawResponse);
        billingRepo.updateOrderStatus(orderId, orderStatus, statusResult.reason());

        if ("VERIFIED".equals(orderStatus)) {
            fulfillmentService.fulfillVerifiedOrder(orderId, userId);
        }
        log.info("verify.et result: order={}, status={}, reason={}, requestId={}",
                orderId, orderStatus, statusResult.reason(), resp.requestId());
    }

    @Transactional
    public OrderResponse submitManualReceipt(UUID userId, ManualReceiptRequest request) {
        // Idempotency check
        if (request.idempotencyKey() != null) {
            Optional<BillingRepository.OrderRow> existing =
                    billingRepo.findOrderByIdempotency(userId, request.idempotencyKey());
            if (existing.isPresent()) {
                return toOrderResponse(existing.get());
            }
        }

        BillingRepository.FullOfferRow offer = billingRepo.findOfferById(request.paymentOfferId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_offer"));

        BillingRepository.PaymentMethodRow method = billingRepo.findPaymentMethodById(request.paymentMethodId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_payment_method"));

        if (!method.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "payment_method_unavailable");
        }
        if (!"MANUAL_TRANSFER".equals(method.paymentChannel())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "not_manual_transfer_method");
        }

        validateMarketMatch(offer, method);

        String orderReference = generateOrderReference();
        Instant expiresAt = Instant.now().plus(billingProps.getPaymentOrderExpiryHours(), ChronoUnit.HOURS);
        String instructionSnapshot = buildManualTransferSnapshot(method, request.additionalNotes());

        BillingRepository.OrderRow order = billingRepo.insertOrder(
                userId, offer.id(), method.id(),
                orderReference, "RECEIPT_SUBMITTED",
                offer.priceMinorUnits(), offer.currency(),
                instructionSnapshot, null, expiresAt,
                request.idempotencyKey()
        );

        billingRepo.insertProof(order.id(), "RECEIPT_UPLOAD",
                method.methodCode(), null,
                request.receiptStorageBucket(), request.receiptStoragePath(),
                offer.priceMinorUnits(), offer.currency());

        log.info("Manual receipt submitted: order={}, user={}, method={}",
                order.id(), userId, method.methodCode());
        return toOrderResponse(order, instructionSnapshot);
    }

    // ── List-endpoint helpers ────────────────────────────────────────────────

    private OrderSummaryDto toOrderSummaryDto(BillingRepository.OrderSummaryRow row) {
        boolean isManual = "MANUAL_TRANSFER".equals(row.paymentChannel());
        String status = row.status();

        boolean canResumePayment = "CREATED".equals(status) || "AWAITING_PAYMENT".equals(status);
        boolean canSubmitPayment = false;
        boolean canCreateNewOrder = "REJECTED".equals(status)
                || "EXPIRED".equals(status)
                || "CANCELLED".equals(status);

        return new OrderSummaryDto(
                row.id(),
                row.orderReference(),
                status,
                row.productCode(),
                row.productType(),
                row.displayName(),
                row.expectedAmountMinorUnits(),
                row.expectedCurrency(),
                formatPrice(row.expectedAmountMinorUnits(), row.expectedCurrency()),
                row.paymentMethodId(),
                row.paymentMethodDisplayName(),
                row.paymentChannel(),
                row.paymentMethod(),
                row.methodCode(),
                row.expiresAt(),
                row.createdAt(),
                row.updatedAt(),
                canResumePayment,
                canSubmitPayment,
                canCreateNewOrder,
                row.verificationCount()
        );
    }

    private static String formatPrice(int minorUnits, String currency) {
        return String.format("%s %.2f", currency, minorUnits / 100.0);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void validateMarketMatch(BillingRepository.FullOfferRow offer,
                                     BillingRepository.PaymentMethodRow method) {
        if (!offer.countryCode().equals(method.countryCode())
                || !offer.platform().equals(method.platform())) {
            log.warn("Market mismatch: offer({},{}) vs method({},{})",
                    offer.countryCode(), offer.platform(),
                    method.countryCode(), method.platform());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "offer_method_market_mismatch");
        }
    }

    private String generateOrderReference() {
        return "QAL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private String buildOnlinePaymentSnapshot(BillingRepository.PaymentMethodRow method,
                                              BillingRepository.FullOfferRow offer,
                                              String orderReference, Instant expiresAt) {
        var pi = billingProps.getPaymentInstructions();
        double amount = offer.priceMinorUnits() / 100.0;
        String formattedAmount = String.format("%.2f", amount);
        String formattedExpiry = EXPIRY_FMT.format(expiresAt);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("paymentChannel", method.paymentChannel());
        snapshot.put("paymentMethod", method.paymentMethod());
        snapshot.put("methodCode", method.methodCode());
        snapshot.put("displayName", method.displayName());
        snapshot.put("orderReference", orderReference);

        String template = method.paymentInstructions();
        if (template != null && !template.isBlank()) {
            String resolved = template
                    .replace("{{EXPECTED_AMOUNT}}", formattedAmount)
                    .replace("{{CURRENCY}}", offer.currency())
                    .replace("{{ORDER_REFERENCE}}", orderReference)
                    .replace("{{ORDER_EXPIRY}}", formattedExpiry)
                    .replace("{{PAYMENT_ACCOUNT_NAME}}", pi.getAccountName() != null ? pi.getAccountName() : "")
                    .replace("{{PAYMENT_ACCOUNT_NUMBER}}", pi.getAccountNumber() != null ? pi.getAccountNumber() : "");
            snapshot.put("instructionText", resolved);
        }

        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String buildManualTransferSnapshot(BillingRepository.PaymentMethodRow method,
                                               Map<String, Object> verificationData) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("paymentChannel", method.paymentChannel());
        snapshot.put("methodCode", method.methodCode());
        snapshot.put("displayName", method.displayName());
        if (verificationData != null && !verificationData.isEmpty()) {
            snapshot.put("submittedVerificationFields", verificationData);
        }
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String extractTransactionRef(String methodCode, Map<String, Object> verificationData) {
        if (verificationData == null) return null;
        return switch (methodCode) {
            case "cbe" -> firstNonNull(verificationData, "referenceNumber", "receiptNumber");
            case "telebirr", "mpesa" -> firstNonNull(verificationData, "transactionOrReference", "reference");
            case "cbebirr" -> firstNonNull(verificationData, "transactionNumber", "reference");
            case "boa", "awash", "dashen", "siinqee", "kaafiebirr" ->
                    firstNonNull(verificationData, "referenceNumber", "reference");
            default -> firstNonNull(verificationData, "referenceNumber", "reference", "transactionOrReference");
        };
    }

    private String firstNonNull(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object val = map.get(key);
            if (val instanceof String s && !s.isBlank()) return s;
        }
        return null;
    }

    private record StatusResult(String status, String reason) {}

    private StatusResult resolveInlineStatus(VerifyEtClient.VerifyEtResponse resp,
                                       int expectedAmountMinorUnits, String expectedCurrency,
                                       String expectedMethodCode) {
        // Terminal failure -> REJECTED
        if ("failed".equals(resp.processingStatus()) || "not_found".equals(resp.status())) {
            return new StatusResult("REJECTED",
                    "processingStatus=" + resp.processingStatus() + ", status=" + resp.status());
        }

        // Must be fully verified: completed + success + verified=true
        if (!resp.verified() || !"completed".equals(resp.processingStatus()) || !"success".equals(resp.status())) {
            return new StatusResult("MANUAL_REVIEW",
                    "not fully verified: processingStatus=" + resp.processingStatus()
                    + ", status=" + resp.status() + ", verified=" + resp.verified());
        }

        // Bank must match
        String verifiedBank = resp.result() != null ? resp.result().bank() : null;
        if (verifiedBank == null || !verifiedBank.equalsIgnoreCase(expectedMethodCode)) {
            return new StatusResult("MANUAL_REVIEW",
                    "bank mismatch: expected=" + expectedMethodCode + ", got=" + verifiedBank);
        }

        // Amount must match
        Integer verifiedAmount = parseVerifyEtAmount(resp);
        if (verifiedAmount != null && verifiedAmount != expectedAmountMinorUnits) {
            return new StatusResult("MANUAL_REVIEW",
                    "amount mismatch: expected=" + expectedAmountMinorUnits
                    + " minor units, got=" + verifiedAmount);
        }

        // Settlement account must be matched, unambiguous, and high confidence
        VerifyEtClient.SettlementAccountMatch sam = resp.result() != null
                ? resp.result().settlementAccountMatch() : null;
        if (sam == null || !sam.matched()) {
            return new StatusResult("MANUAL_REVIEW", "settlement account not matched");
        }
        if (sam.ambiguous()) {
            return new StatusResult("MANUAL_REVIEW", "settlement account match is ambiguous");
        }
        if (!"high".equalsIgnoreCase(sam.matchConfidence())) {
            return new StatusResult("MANUAL_REVIEW",
                    "settlement match confidence not high: " + sam.matchConfidence());
        }

        // Transfer timestamp max-age check (data.timestamp = actual bank transfer time)
        String transferTs = resp.result().transactionTimestamp();
        if (transferTs != null) {
            try {
                java.time.Instant transferTime = java.time.Instant.parse(transferTs);
                long maxAgeHours = billingProps.getManualTransferMaxAgeHours();
                java.time.Instant cutoff = java.time.Instant.now().minus(java.time.Duration.ofHours(maxAgeHours));
                if (transferTime.isBefore(cutoff)) {
                    log.warn("verify.et inline: transfer timestamp {} is older than {}h", transferTs, maxAgeHours);
                    return new StatusResult("EXPIRED",
                            "transfer older than " + maxAgeHours + "h (transfer time: " + transferTs + ")");
                }
            } catch (Exception e) {
                log.warn("verify.et inline: could not parse transfer timestamp: {}", transferTs);
            }
        }

        return new StatusResult("VERIFIED", null);
    }

    private Integer parseVerifyEtAmount(VerifyEtClient.VerifyEtResponse resp) {
        if (resp.result() == null || resp.result().amount() == null) return null;
        try {
            return (int) Math.round(Double.parseDouble(resp.result().amount()) * 100);
        } catch (Exception e) {
            return null;
        }
    }

    private String toJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); }
        catch (Exception e) { return "{}"; }
    }

    private OrderResponse toOrderResponse(BillingRepository.OrderRow order) {
        return toOrderResponse(order, null);
    }

    @SuppressWarnings("unchecked")
    private OrderResponse toOrderResponse(BillingRepository.OrderRow order, String instructionsJson) {
        Map<String, Object> instructions = Map.of();
        if (instructionsJson != null) {
            try { instructions = objectMapper.readValue(instructionsJson, Map.class); }
            catch (Exception ignored) {}
        }

        String status = order.status();
        String verifyEtRequestId = null;
        if ("VERIFICATION_PENDING".equals(status) || "MANUAL_REVIEW".equals(status)) {
            verifyEtRequestId = billingRepo.findVerifyEtRequestIdByOrderId(order.id()).orElse(null);
        }

        Long pollAfterMs = "VERIFICATION_PENDING".equals(status) ? 5000L : null;
        boolean canRetryVerification = false;
        boolean canUploadReceipt = "MANUAL_REVIEW".equals(status);
        boolean canContactSupport = "REJECTED".equals(status) || "MANUAL_REVIEW".equals(status);

        return new OrderResponse(
                order.id(), order.orderReference(), status,
                order.statusReason(),
                order.paymentOfferId(),
                order.expectedAmountMinorUnits(), order.expectedCurrency(),
                order.paymentMethodId(),
                order.paymentChannel(), order.paymentMethod(), order.methodCode(), order.paymentMethodDisplayName(),
                order.providerCheckoutUrl(),
                instructions, order.expiresAt(), order.createdAt(), order.updatedAt(),
                verifyEtRequestId, pollAfterMs,
                canRetryVerification, canUploadReceipt, canContactSupport,
                order.verificationCount()
        );
    }
}
