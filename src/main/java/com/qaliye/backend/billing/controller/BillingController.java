package com.qaliye.backend.billing.controller;

import com.qaliye.backend.billing.dto.*;
import com.qaliye.backend.billing.service.BoostService;
import com.qaliye.backend.billing.service.CountrySettingsService;
import com.qaliye.backend.billing.service.EntitlementService;
import com.qaliye.backend.billing.service.OfferService;
import com.qaliye.backend.billing.service.OrderService;
import com.qaliye.backend.common.CallerUtils;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {

    private static final Logger log = LoggerFactory.getLogger(BillingController.class);

    private final EntitlementService entitlementService;
    private final OfferService offerService;
    private final OrderService orderService;
    private final BoostService boostService;
    private final CountrySettingsService countrySettingsService;

    public BillingController(EntitlementService entitlementService,
                             OfferService offerService,
                             OrderService orderService,
                             BoostService boostService,
                             CountrySettingsService countrySettingsService) {
        this.entitlementService = entitlementService;
        this.offerService = offerService;
        this.orderService = orderService;
        this.boostService = boostService;
        this.countrySettingsService = countrySettingsService;
    }

    @GetMapping("/entitlements")
    public ResponseEntity<EntitlementResponse> getEntitlements() {
        UUID userId = CallerUtils.callerId();
        return ResponseEntity.ok(entitlementService.getEntitlements(userId));
    }

    @GetMapping("/offers")
    public ResponseEntity<List<OfferDto>> getOffers(
            @RequestParam(defaultValue = "MOBILE") String platform) {
        UUID userId = CallerUtils.callerId();
        String normalized = normalizePlatform(platform);
        List<OfferDto> offers = offerService.getOffers(userId, normalized, platform);
        log.debug("GET /api/v1/billing/offers for user={}, platform={} returning {} offers: {}",
                userId, normalized, offers.size(), offers);
        return ResponseEntity.ok(offers);
    }

    @GetMapping("/payment-channels")
    public ResponseEntity<PaymentChannelsResponse> getPaymentChannels(
            @RequestParam(defaultValue = "MOBILE") String platform) {
        UUID userId = CallerUtils.callerId();
        return ResponseEntity.ok(offerService.getPaymentChannels(userId, normalizePlatform(platform)));
    }

    @GetMapping("/payment-options")
    public ResponseEntity<PaymentOptionsResponse> getPaymentOptions(
            @RequestParam(defaultValue = "MOBILE") String platform,
            @RequestParam(required = false) String channel) {
        UUID userId = CallerUtils.callerId();
        String normalized = normalizePlatform(platform);
        PaymentOptionsResponse response;
        if (channel != null && !channel.isBlank()) {
            response = offerService.getPaymentMethodsByChannel(
                    userId, normalized, channel.toUpperCase());
        } else {
            response = offerService.getPaymentOptions(userId, normalized);
        }
        log.debug("GET /api/v1/billing/payment-options user={} platform={} channel={} response={}",
                userId, normalized, channel, response);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/orders")
    public ResponseEntity<OrderListResponse> listOrders(
            @RequestParam(required = false) String statuses,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        UUID userId = CallerUtils.callerId();
        List<String> parsedStatuses = (statuses != null && !statuses.isBlank())
                ? Arrays.stream(statuses.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList()
                : Collections.emptyList();
        return ResponseEntity.ok(orderService.listOrders(userId,
                parsedStatuses.isEmpty() ? null : parsedStatuses, page, pageSize));
    }

    @PostMapping("/orders")
    public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        UUID userId = CallerUtils.callerId();
        log.info("createOrder request: paymentOfferId={}, paymentMethodId={}, platform={}, idempotencyKey={}",
                request.paymentOfferId(), request.paymentMethodId(), request.platform(), request.idempotencyKey());
        OrderResponse response = orderService.createOrder(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/orders/{orderId}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable UUID orderId) {
        UUID userId = CallerUtils.callerId();
        return ResponseEntity.ok(orderService.getOrder(userId, orderId));
    }

    @PostMapping("/orders/{orderId}/verify")
    public ResponseEntity<OrderResponse> verifyPayment(@PathVariable UUID orderId) {
        UUID userId = CallerUtils.callerId();
        return ResponseEntity.ok(orderService.verifyChapaPayment(userId, orderId));
    }

    @PostMapping("/manual-transfer/verify")
    public ResponseEntity<OrderResponse> submitManualTransferVerification(
            @Valid @RequestBody ManualTransferVerifyRequest request) {
        UUID userId = CallerUtils.callerId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(orderService.submitManualTransferVerification(userId, request));
    }

    @PostMapping("/manual-transfer/receipt")
    public ResponseEntity<OrderResponse> submitManualReceipt(
            @Valid @RequestBody ManualReceiptRequest request) {
        UUID userId = CallerUtils.callerId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(orderService.submitManualReceipt(userId, request));
    }

    @PostMapping("/boosts/activate")
    public ResponseEntity<BoostActivationResponse> activateBoost(
            @RequestBody(required = false) BoostActivationRequest request) {
        UUID userId = CallerUtils.callerId();
        String idempotencyKey = request != null ? request.idempotencyKey() : null;
        return ResponseEntity.ok(boostService.activateBoost(userId, idempotencyKey));
    }

    @GetMapping("/country-settings")
    public ResponseEntity<CountrySettingsService.CountrySettings> getCountrySettings() {
        UUID userId = CallerUtils.callerId();
        return ResponseEntity.ok(countrySettingsService.getSettingsForUser(userId));
    }

    private static String normalizePlatform(String platform) {
        if (platform == null) return "MOBILE";
        String upper = platform.toUpperCase();
        return switch (upper) {
            case "ANDROID", "IOS" -> "MOBILE";
            default -> upper;
        };
    }
}
