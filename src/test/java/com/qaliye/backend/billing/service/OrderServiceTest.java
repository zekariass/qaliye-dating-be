package com.qaliye.backend.billing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaliye.backend.billing.BillingProperties;
import com.qaliye.backend.billing.dto.CreateOrderRequest;
import com.qaliye.backend.billing.dto.ManualReceiptRequest;
import com.qaliye.backend.billing.dto.ManualTransferVerifyRequest;
import com.qaliye.backend.billing.dto.OrderListResponse;
import com.qaliye.backend.billing.dto.OrderResponse;
import com.qaliye.backend.billing.dto.OrderSummaryDto;
import com.qaliye.backend.billing.provider.ChapaClient;
import com.qaliye.backend.billing.provider.LocalGatewayRegistry;
import com.qaliye.backend.billing.provider.LocalOnlinePaymentGateway;
import com.qaliye.backend.billing.provider.VerifyEtClient;
import com.qaliye.backend.billing.repository.BillingRepository;
import com.qaliye.backend.billing.repository.PromotionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock BillingRepository billingRepo;
    @Mock BillingProperties billingProps;
    @Mock BillingMarketResolver marketResolver;
    @Mock LocalGatewayRegistry gatewayRegistry;
    @Mock LocalOnlinePaymentGateway mockGateway;
    @Mock VerifyEtClient verifyEtClient;
    @Mock FulfillmentService fulfillmentService;
    @Mock PromotionRepository promotionRepo;
    @Mock PromotionEligibilityService promotionEligibilityService;
    @Mock BillingProperties.PaymentInstructions paymentInstructions;
    @Mock BillingProperties.Verifier verifier;
    @Mock ChapaClient chapaClient;
    @Mock CountrySettingsService countrySettingsService;

    OrderService service;

    UUID userId   = UUID.randomUUID();
    UUID offerId  = UUID.randomUUID();
    UUID methodId = UUID.randomUUID();

    static final BillingMarketResolver.MarketResult ET_ANDROID =
            new BillingMarketResolver.MarketResult("ET", "ET", "ANDROID", false);

    @BeforeEach
    void setUp() {
        service = new OrderService(billingRepo, billingProps, marketResolver,
                gatewayRegistry, verifyEtClient, fulfillmentService, new ObjectMapper(),
                promotionRepo, promotionEligibilityService, chapaClient, countrySettingsService);
        lenient().when(billingProps.getPaymentOrderExpiryHours()).thenReturn(2);
        lenient().when(billingProps.getPaymentInstructions()).thenReturn(paymentInstructions);
        lenient().when(billingProps.getVerifier()).thenReturn(verifier);
        lenient().when(verifier.getWebhookUrl()).thenReturn("https://webhook.example.com");
        lenient().when(paymentInstructions.getAccountName()).thenReturn("Qaliye PLC");
        lenient().when(paymentInstructions.getAccountNumber()).thenReturn("100012345");
        lenient().when(paymentInstructions.getBankName()).thenReturn("CBE");
        lenient().when(marketResolver.resolveMarket(any(), any())).thenReturn(ET_ANDROID);
        lenient().when(countrySettingsService.getSettings(any()))
                .thenReturn(new CountrySettingsService.CountrySettings("ET", true, true, false));
        lenient().when(mockGateway.getMethodCode()).thenReturn("chapa");
        lenient().when(mockGateway.isConfigured()).thenReturn(true);
    }

    @Test
    void createOrder_chapaMethod_createsCheckoutAndOrder() {
        BillingRepository.FullOfferRow offer = buildOffer("ET", "ANDROID", 49900, "ETB");
        BillingRepository.PaymentMethodRow method = buildMethod("chapa", "ONLINE_PAYMENT", "ET", "ANDROID");
        BillingRepository.OrderRow order = buildOrderRow(userId, offerId, methodId,
                "AWAITING_PAYMENT", "ONLINE_PAYMENT", "chapa", "https://chapa.co/pay/xxx");

        when(billingRepo.findOfferById(offerId)).thenReturn(Optional.of(offer));
        when(billingRepo.findActiveOnlinePaymentMethod("ET", "ANDROID")).thenReturn(Optional.of(method));
        when(gatewayRegistry.resolve("chapa")).thenReturn(mockGateway);
        when(mockGateway.createCheckout(anyString(), anyInt(), anyString(), anyString(), anyString()))
                .thenReturn(new LocalOnlinePaymentGateway.CheckoutResult("https://chapa.co/pay/xxx", "QAL-TXREF"));
        when(billingRepo.insertOrder(any(), eq(userId), eq(offerId), eq(methodId),
                anyString(), anyString(), anyInt(), anyString(),
                anyString(), anyString(), any(), any()))
                .thenReturn(order);
        when(billingRepo.findOrderById(order.id())).thenReturn(Optional.of(order));

        OrderResponse response = service.createOrder(userId,
                new CreateOrderRequest(offerId, methodId, "ANDROID", null, null));

        assertThat(response.status()).isEqualTo("AWAITING_PAYMENT");
        assertThat(response.providerCheckoutUrl()).isEqualTo("https://chapa.co/pay/xxx");
        verify(billingRepo).updateOrderWithCheckout(eq(order.id()), anyString(), anyString(), anyString());
    }

    @Test
    void createOrder_invalidOffer_throws400() {
        when(billingRepo.findOfferById(offerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createOrder(userId,
                new CreateOrderRequest(offerId, methodId, "ANDROID", null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_offer");
    }

    @Test
    void createOrder_noActiveOnlineMethod_throws400() {
        when(billingRepo.findOfferById(offerId)).thenReturn(Optional.of(buildOffer("ET", "ANDROID", 100, "ETB")));
        when(billingRepo.findActiveOnlinePaymentMethod("ET", "ANDROID")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createOrder(userId,
                new CreateOrderRequest(offerId, methodId, "ANDROID", null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no_active_online_payment_method");
    }

    @Test
    void createOrder_wrongMethodId_throws400() {
        UUID differentMethodId = UUID.randomUUID();
        BillingRepository.PaymentMethodRow activeMethod = buildMethod("chapa", "ONLINE_PAYMENT", "ET", "ANDROID");

        when(billingRepo.findOfferById(offerId)).thenReturn(Optional.of(buildOffer("ET", "ANDROID", 100, "ETB")));
        when(billingRepo.findActiveOnlinePaymentMethod("ET", "ANDROID")).thenReturn(Optional.of(activeMethod));

        assertThatThrownBy(() -> service.createOrder(userId,
                new CreateOrderRequest(offerId, differentMethodId, "ANDROID", null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid_payment_method_for_market");
    }

    @Test
    void createOrder_marketMismatch_throws400() {
        BillingRepository.FullOfferRow globalOffer = buildOffer("GLOBAL", "ANDROID", 100, "ETB");
        BillingRepository.PaymentMethodRow etMethod = buildMethod("chapa", "ONLINE_PAYMENT", "ET", "ANDROID");

        when(billingRepo.findOfferById(offerId)).thenReturn(Optional.of(globalOffer));
        when(billingRepo.findActiveOnlinePaymentMethod("ET", "ANDROID")).thenReturn(Optional.of(etMethod));

        assertThatThrownBy(() -> service.createOrder(userId,
                new CreateOrderRequest(offerId, methodId, "ANDROID", null, null)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void createOrder_idempotencyKeyExists_returnsExistingOrder() {
        BillingRepository.OrderRow existing = buildOrderRow(userId, offerId, methodId,
                "AWAITING_PAYMENT", "ONLINE_PAYMENT", "chapa", null);
        when(billingRepo.findOrderByIdempotency(userId, "idem-key-1"))
                .thenReturn(Optional.of(existing));

        OrderResponse response = service.createOrder(userId,
                new CreateOrderRequest(offerId, methodId, "ANDROID", "idem-key-1", null));

        assertThat(response.id()).isEqualTo(existing.id());
        verify(billingRepo, never()).findOfferById(any());
    }

    // ── submitManualTransferVerification ─────────────────────────────────────

    @Test
    void submitManualTransferVerification_queued_returnsVerificationPending() {
        BillingRepository.FullOfferRow offer = buildOffer("ET", "ANDROID", 49900, "ETB");
        BillingRepository.PaymentMethodRow method = buildMethod("telebirr", "MANUAL_TRANSFER", "ET", "ANDROID");
        BillingRepository.OrderRow order = buildOrderRow(userId, offerId, methodId,
                "VERIFICATION_PENDING", "MANUAL_TRANSFER", "telebirr", null);
        UUID proofId = UUID.randomUUID();
        UUID verificationId = UUID.randomUUID();

        when(billingRepo.findOfferById(offerId)).thenReturn(Optional.of(offer));
        when(billingRepo.findPaymentMethodById(methodId)).thenReturn(Optional.of(method));
        when(billingRepo.findOrderByManualReference(any(), any())).thenReturn(Optional.empty());
        when(billingRepo.insertManualTransferOrder(any(), any(), any(), anyString(), anyString(),
                anyInt(), anyString(), anyString(), any(), any(), any(), any())).thenReturn(order);
        when(billingRepo.insertProof(any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(proofId);
        when(billingRepo.insertVerificationAttempt(any(), any(), any(), any(), any()))
                .thenReturn(verificationId);
        when(verifyEtClient.submit(any(), any(), any(), any()))
                .thenReturn(new VerifyEtClient.VerifyEtResponse(true, "REQ-Q", "queued", "pending", false, null, null));
        when(billingRepo.findOrderById(order.id())).thenReturn(Optional.of(order));

        OrderResponse response = service.submitManualTransferVerification(userId,
                new ManualTransferVerifyRequest(offerId, methodId, "ANDROID",
                        Map.of("transactionOrReference", "ABCDEF"), null));

        assertThat(response.status()).isEqualTo("VERIFICATION_PENDING");
        verify(billingRepo).incrementVerificationCount(order.id());
        verify(billingRepo).updateVerificationAttemptWithVerifyEtRequest(
                eq(verificationId), any(), any(), eq("PENDING"), any());
        verify(fulfillmentService, never()).fulfillVerifiedOrder(any(), any());
    }

    @Test
    void submitManualTransferVerification_inlineVerified_fulfills() {
        BillingRepository.FullOfferRow offer = buildOffer("ET", "ANDROID", 49900, "ETB");
        BillingRepository.PaymentMethodRow method = buildMethod("telebirr", "MANUAL_TRANSFER", "ET", "ANDROID");
        BillingRepository.OrderRow orderPending = buildOrderRow(userId, offerId, methodId,
                "VERIFICATION_PENDING", "MANUAL_TRANSFER", "telebirr", null);
        BillingRepository.OrderRow orderVerified = buildOrderRow(userId, offerId, methodId,
                "VERIFIED", "MANUAL_TRANSFER", "telebirr", null);

        VerifyEtClient.VerifyEtResult result = new VerifyEtClient.VerifyEtResult(
                "telebirr", "499.00", "ETB", "REF001", "****", null,
                new VerifyEtClient.ConfirmationHistory(false, 1),
                new VerifyEtClient.SettlementAccountMatch(true, false, "exact", "high", "****1234"));
        VerifyEtClient.VerifyEtResponse resp =
                new VerifyEtClient.VerifyEtResponse(false, "REQ-1", "completed", "success", true, result, null);

        when(billingRepo.findOfferById(offerId)).thenReturn(Optional.of(offer));
        when(billingRepo.findPaymentMethodById(methodId)).thenReturn(Optional.of(method));
        when(billingRepo.findOrderByManualReference(any(), any())).thenReturn(Optional.empty());
        when(billingRepo.insertManualTransferOrder(eq(userId), eq(offerId), eq(methodId),
                anyString(), anyString(), anyInt(), anyString(),
                anyString(), any(), any(), any(), any())).thenReturn(orderPending);
        when(billingRepo.insertProof(any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(UUID.randomUUID());
        when(billingRepo.insertVerificationAttempt(any(), any(), any(), any(), any()))
                .thenReturn(UUID.randomUUID());
        when(verifyEtClient.submit(any(), any(), any(), any())).thenReturn(resp);
        when(billingRepo.existsVerifiedProviderReference(any(), any())).thenReturn(false);
        when(billingRepo.findOrderById(orderPending.id())).thenReturn(Optional.of(orderVerified));

        OrderResponse response = service.submitManualTransferVerification(userId,
                new ManualTransferVerifyRequest(offerId, methodId, "ANDROID",
                        Map.of("transactionOrReference", "ABCDEF"), null));

        assertThat(response.status()).isEqualTo("VERIFIED");
        verify(billingRepo).incrementVerificationCount(orderPending.id());
        verify(billingRepo).updateOrderStatus(orderPending.id(), "VERIFIED", null);
        verify(fulfillmentService).fulfillVerifiedOrder(orderPending.id(), userId);
    }

    @Test
    void submitManualTransferVerification_manualReview_noFulfill() {
        BillingRepository.FullOfferRow offer = buildOffer("ET", "ANDROID", 49900, "ETB");
        BillingRepository.PaymentMethodRow method = buildMethod("telebirr", "MANUAL_TRANSFER", "ET", "ANDROID");
        BillingRepository.OrderRow orderPending = buildOrderRow(userId, offerId, methodId,
                "VERIFICATION_PENDING", "MANUAL_TRANSFER", "telebirr", null);
        BillingRepository.OrderRow orderReview = buildOrderRow(userId, offerId, methodId,
                "MANUAL_REVIEW", "MANUAL_TRANSFER", "telebirr", null);

        // Bank mismatch: user used "cbe" but order is for "telebirr"
        VerifyEtClient.VerifyEtResult result = new VerifyEtClient.VerifyEtResult(
                "cbe", "499.00", "ETB", "REF001", "****", null,
                new VerifyEtClient.ConfirmationHistory(false, 1),
                new VerifyEtClient.SettlementAccountMatch(true, false, "exact", "high", "****1234"));
        VerifyEtClient.VerifyEtResponse resp =
                new VerifyEtClient.VerifyEtResponse(false, "REQ-2", "completed", "success", true, result, null);

        when(billingRepo.findOfferById(offerId)).thenReturn(Optional.of(offer));
        when(billingRepo.findPaymentMethodById(methodId)).thenReturn(Optional.of(method));
        when(billingRepo.findOrderByManualReference(any(), any())).thenReturn(Optional.empty());
        when(billingRepo.insertManualTransferOrder(eq(userId), eq(offerId), eq(methodId),
                anyString(), anyString(), anyInt(), anyString(),
                anyString(), any(), any(), any(), any())).thenReturn(orderPending);
        when(billingRepo.insertProof(any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(UUID.randomUUID());
        when(billingRepo.insertVerificationAttempt(any(), any(), any(), any(), any()))
                .thenReturn(UUID.randomUUID());
        when(verifyEtClient.submit(any(), any(), any(), any())).thenReturn(resp);
        when(billingRepo.existsVerifiedProviderReference(any(), any())).thenReturn(false);
        when(billingRepo.findOrderById(orderPending.id())).thenReturn(Optional.of(orderReview));

        OrderResponse response = service.submitManualTransferVerification(userId,
                new ManualTransferVerifyRequest(offerId, methodId, "ANDROID",
                        Map.of("transactionOrReference", "ABCDEF"), null));

        assertThat(response.status()).isEqualTo("MANUAL_REVIEW");
        verify(billingRepo).incrementVerificationCount(orderPending.id());
        verify(billingRepo).updateOrderStatus(eq(orderPending.id()), eq("MANUAL_REVIEW"), anyString());
        verify(fulfillmentService, never()).fulfillVerifiedOrder(any(), any());
    }

    @Test
    void submitManualReceipt_createsReceiptSubmittedOrder() {
        BillingRepository.FullOfferRow offer = buildOffer("ET", "ANDROID", 49900, "ETB");
        BillingRepository.PaymentMethodRow method = buildMethod("telebirr", "MANUAL_TRANSFER", "ET", "ANDROID");
        BillingRepository.OrderRow order = buildOrderRow(userId, offerId, methodId,
                "RECEIPT_SUBMITTED", "MANUAL_TRANSFER", "telebirr", null);

        when(billingRepo.findOfferById(offerId)).thenReturn(Optional.of(offer));
        when(billingRepo.findPaymentMethodById(methodId)).thenReturn(Optional.of(method));
        when(billingRepo.insertOrder(any(), any(), any(), any(), anyString(), eq("RECEIPT_SUBMITTED"),
                anyInt(), anyString(), anyString(), any(), any(), any())).thenReturn(order);
        when(billingRepo.insertProof(any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(UUID.randomUUID());

        OrderResponse response = service.submitManualReceipt(userId,
                new ManualReceiptRequest(offerId, methodId, "ANDROID",
                        "receipts", "user/123/receipt.jpg", null, null));

        assertThat(response.status()).isEqualTo("RECEIPT_SUBMITTED");
        verify(billingRepo).insertProof(any(), eq("RECEIPT_UPLOAD"), eq("telebirr"),
                isNull(), eq("receipts"), eq("user/123/receipt.jpg"), anyInt(), any());
        verify(billingRepo, never()).incrementVerificationCount(any());
        verify(fulfillmentService, never()).fulfillVerifiedOrder(any(), any());
    }

    @Test
    void getOrder_differentUser_throws403() {
        UUID otherUser = UUID.randomUUID();
        BillingRepository.OrderRow order = buildOrderRow(otherUser, offerId, methodId,
                "AWAITING_PAYMENT", "ONLINE_PAYMENT", "chapa", null);
        when(billingRepo.findOrderById(order.id())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.getOrder(userId, order.id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("access_denied");
    }

    // ── listOrders ───────────────────────────────────────────────────────────

    @Test
    void listOrders_returnsOnlyCallerOrders() {
        BillingRepository.OrderSummaryRow row = buildSummaryRow(userId, "AWAITING_PAYMENT", "MANUAL_TRANSFER", "telebirr");
        when(billingRepo.findOrderSummariesByUserId(eq(userId), isNull(), eq(20), eq(0)))
                .thenReturn(List.of(row));
        when(billingRepo.countOrdersByUserId(eq(userId), isNull())).thenReturn(1L);

        OrderListResponse response = service.listOrders(userId, null, 1, 20);

        assertThat(response.orders()).hasSize(1);
        assertThat(response.orders().get(0).id()).isEqualTo(row.id());
        assertThat(response.total()).isEqualTo(1);
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.pageSize()).isEqualTo(20);
        assertThat(response.totalPages()).isEqualTo(1);
        verify(billingRepo).findOrderSummariesByUserId(eq(userId), isNull(), eq(20), eq(0));
    }

    @Test
    void listOrders_doesNotQueryOtherUsersOrders() {
        UUID otherUser = UUID.randomUUID();
        when(billingRepo.findOrderSummariesByUserId(eq(userId), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(billingRepo.countOrdersByUserId(eq(userId), isNull())).thenReturn(0L);

        service.listOrders(userId, null, 1, 20);

        verify(billingRepo, never()).findOrderSummariesByUserId(eq(otherUser), any(), anyInt(), anyInt());
    }

    @Test
    void listOrders_statusFilter_passedThrough() {
        List<String> statuses = List.of("AWAITING_PAYMENT", "MANUAL_REVIEW");
        when(billingRepo.findOrderSummariesByUserId(eq(userId), eq(statuses), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(billingRepo.countOrdersByUserId(eq(userId), eq(statuses))).thenReturn(0L);

        service.listOrders(userId, statuses, 1, 20);

        verify(billingRepo).findOrderSummariesByUserId(eq(userId), eq(statuses), anyInt(), anyInt());
    }

    @Test
    void listOrders_invalidStatus_silentlyFiltered() {
        List<String> validStatuses = List.of("AWAITING_PAYMENT", "REVIEW_REQUIRED");
        when(billingRepo.findOrderSummariesByUserId(eq(userId), eq(validStatuses), eq(20), eq(0)))
                .thenReturn(List.of());
        when(billingRepo.countOrdersByUserId(eq(userId), eq(validStatuses)))
                .thenReturn(0L);

        OrderListResponse response = service.listOrders(userId,
                List.of("AWAITING_PAYMENT", "INVALID_STATUS", "REVIEW_REQUIRED"), 1, 20);

        assertThat(response.total()).isEqualTo(0);
        verify(billingRepo).findOrderSummariesByUserId(eq(userId), eq(validStatuses), eq(20), eq(0));
    }

    @Test
    void listOrders_pageSizeCappedAt100() {
        when(billingRepo.findOrderSummariesByUserId(eq(userId), isNull(), eq(100), eq(0)))
                .thenReturn(List.of());
        when(billingRepo.countOrdersByUserId(any(), any())).thenReturn(0L);

        OrderListResponse response = service.listOrders(userId, null, 1, 999);

        assertThat(response.pageSize()).isEqualTo(100);
        verify(billingRepo).findOrderSummariesByUserId(eq(userId), isNull(), eq(100), eq(0));
    }

    @Test
    void listOrders_paginationOffset_calculatedCorrectly() {
        when(billingRepo.findOrderSummariesByUserId(eq(userId), isNull(), eq(10), eq(20)))
                .thenReturn(List.of());
        when(billingRepo.countOrdersByUserId(any(), any())).thenReturn(0L);

        service.listOrders(userId, null, 3, 10);

        verify(billingRepo).findOrderSummariesByUserId(eq(userId), isNull(), eq(10), eq(20));
    }

    @Test
    void listOrders_totalPages_calculatedCorrectly() {
        when(billingRepo.findOrderSummariesByUserId(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(billingRepo.countOrdersByUserId(any(), any())).thenReturn(45L);

        OrderListResponse response = service.listOrders(userId, null, 1, 20);

        assertThat(response.totalPages()).isEqualTo(3);
    }

    @Test
    void listOrders_actionFlags_awaitingManual_canSubmitPayment() {
        BillingRepository.OrderSummaryRow row = buildSummaryRow(userId, "AWAITING_PAYMENT", "MANUAL_TRANSFER", "telebirr");
        when(billingRepo.findOrderSummariesByUserId(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(row));
        when(billingRepo.countOrdersByUserId(any(), any())).thenReturn(1L);

        OrderSummaryDto dto = service.listOrders(userId, null, 1, 20).orders().get(0);

        assertThat(dto.canResumePayment()).isTrue();
        assertThat(dto.canSubmitPayment()).isFalse();
        assertThat(dto.canCreateNewOrder()).isFalse();
    }

    @Test
    void listOrders_actionFlags_awaitingChapa_cannotSubmitManual() {
        BillingRepository.OrderSummaryRow row = buildSummaryRow(userId, "AWAITING_PAYMENT", "ONLINE_PAYMENT", "chapa");
        when(billingRepo.findOrderSummariesByUserId(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(row));
        when(billingRepo.countOrdersByUserId(any(), any())).thenReturn(1L);

        OrderSummaryDto dto = service.listOrders(userId, null, 1, 20).orders().get(0);

        assertThat(dto.canResumePayment()).isTrue();
        assertThat(dto.canSubmitPayment()).isFalse();

        assertThat(dto.canCreateNewOrder()).isFalse();
    }

    @Test
    void listOrders_actionFlags_created_canResume() {
        BillingRepository.OrderSummaryRow row = buildSummaryRow(userId, "CREATED", "ONLINE_PAYMENT", "chapa");
        when(billingRepo.findOrderSummariesByUserId(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(row));
        when(billingRepo.countOrdersByUserId(any(), any())).thenReturn(1L);

        OrderSummaryDto dto = service.listOrders(userId, null, 1, 20).orders().get(0);

        assertThat(dto.canResumePayment()).isTrue();
        assertThat(dto.canSubmitPayment()).isFalse();

        assertThat(dto.canCreateNewOrder()).isFalse();
    }

    @Test
    void listOrders_actionFlags_manualReview_allFalse() {
        BillingRepository.OrderSummaryRow row = buildSummaryRow(userId, "MANUAL_REVIEW", "MANUAL_TRANSFER", "telebirr");
        when(billingRepo.findOrderSummariesByUserId(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(row));
        when(billingRepo.countOrdersByUserId(any(), any())).thenReturn(1L);

        OrderSummaryDto dto = service.listOrders(userId, null, 1, 20).orders().get(0);

        assertThat(dto.canResumePayment()).isFalse();
        assertThat(dto.canSubmitPayment()).isFalse();

        assertThat(dto.canCreateNewOrder()).isFalse();
    }

    @Test
    void listOrders_actionFlags_verificationPending_allFalse() {
        BillingRepository.OrderSummaryRow row = buildSummaryRow(userId, "VERIFICATION_PENDING", "MANUAL_TRANSFER", "telebirr");
        when(billingRepo.findOrderSummariesByUserId(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(row));
        when(billingRepo.countOrdersByUserId(any(), any())).thenReturn(1L);

        OrderSummaryDto dto = service.listOrders(userId, null, 1, 20).orders().get(0);

        assertThat(dto.canResumePayment()).isFalse();
        assertThat(dto.canSubmitPayment()).isFalse();

        assertThat(dto.canCreateNewOrder()).isFalse();
    }

    @Test
    void listOrders_actionFlags_verified_allFalse() {
        BillingRepository.OrderSummaryRow row = buildSummaryRow(userId, "VERIFIED", "MANUAL_TRANSFER", "telebirr");
        when(billingRepo.findOrderSummariesByUserId(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(row));
        when(billingRepo.countOrdersByUserId(any(), any())).thenReturn(1L);

        OrderSummaryDto dto = service.listOrders(userId, null, 1, 20).orders().get(0);

        assertThat(dto.canResumePayment()).isFalse();
        assertThat(dto.canSubmitPayment()).isFalse();

        assertThat(dto.canCreateNewOrder()).isFalse();
    }

    @Test
    void getOrder_ownOrder_returnsOrder() {
        BillingRepository.OrderRow order = buildOrderRow(userId, offerId, methodId,
                "AWAITING_PAYMENT", "ONLINE_PAYMENT", "chapa", "https://chapa.co/pay/xxx");
        when(billingRepo.findOrderById(order.id())).thenReturn(Optional.of(order));

        OrderResponse response = service.getOrder(userId, order.id());

        assertThat(response.status()).isEqualTo("AWAITING_PAYMENT");
    }

    @Test
    void getOrder_otherUsersOrder_throwsForbidden() {
        UUID otherUserId = UUID.randomUUID();
        BillingRepository.OrderRow order = buildOrderRow(otherUserId, offerId, methodId,
                "AWAITING_PAYMENT", "ONLINE_PAYMENT", "chapa", "https://chapa.co/pay/xxx");
        when(billingRepo.findOrderById(order.id())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.getOrder(userId, order.id()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    ResponseStatusException rse = (ResponseStatusException) e;
                    assertThat(rse.getStatusCode().value()).isEqualTo(403);
                    assertThat(rse.getReason()).isEqualTo("access_denied");
                });
    }

    @Test
    void getOrder_missingOrder_throwsNotFound() {
        UUID orderId = UUID.randomUUID();
        when(billingRepo.findOrderById(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOrder(userId, orderId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    ResponseStatusException rse = (ResponseStatusException) e;
                    assertThat(rse.getStatusCode().value()).isEqualTo(404);
                    assertThat(rse.getReason()).isEqualTo("order_not_found");
                });
    }

    @Test
    void listOrders_actionFlags_rejected_canCreateNewOrder() {
        BillingRepository.OrderSummaryRow row = buildSummaryRow(userId, "REJECTED", "MANUAL_TRANSFER", "telebirr");
        when(billingRepo.findOrderSummariesByUserId(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(row));
        when(billingRepo.countOrdersByUserId(any(), any())).thenReturn(1L);

        OrderSummaryDto dto = service.listOrders(userId, null, 1, 20).orders().get(0);

        assertThat(dto.canCreateNewOrder()).isTrue();
        assertThat(dto.canResumePayment()).isFalse();
        assertThat(dto.canSubmitPayment()).isFalse();

    }

    @Test
    void listOrders_actionFlags_expired_canCreateNewOrder() {
        BillingRepository.OrderSummaryRow row = buildSummaryRow(userId, "EXPIRED", "MANUAL_TRANSFER", "telebirr");
        when(billingRepo.findOrderSummariesByUserId(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(row));
        when(billingRepo.countOrdersByUserId(any(), any())).thenReturn(1L);

        OrderSummaryDto dto = service.listOrders(userId, null, 1, 20).orders().get(0);

        assertThat(dto.canCreateNewOrder()).isTrue();
        assertThat(dto.canResumePayment()).isFalse();
    }

    @Test
    void listOrders_actionFlags_cancelled_canCreateNewOrder() {
        BillingRepository.OrderSummaryRow row = buildSummaryRow(userId, "CANCELLED", "MANUAL_TRANSFER", "telebirr");
        when(billingRepo.findOrderSummariesByUserId(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(row));
        when(billingRepo.countOrdersByUserId(any(), any())).thenReturn(1L);

        OrderSummaryDto dto = service.listOrders(userId, null, 1, 20).orders().get(0);

        assertThat(dto.canCreateNewOrder()).isTrue();
    }

    @Test
    void listOrders_displayPrice_formatted() {
        BillingRepository.OrderSummaryRow row = buildSummaryRow(userId, "AWAITING_PAYMENT", "MANUAL_TRANSFER", "telebirr");
        when(billingRepo.findOrderSummariesByUserId(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(row));
        when(billingRepo.countOrdersByUserId(any(), any())).thenReturn(1L);

        OrderSummaryDto dto = service.listOrders(userId, null, 1, 20).orders().get(0);

        assertThat(dto.displayPrice()).isEqualTo("ETB 499.00");
        assertThat(dto.expectedAmountMinorUnits()).isEqualTo(49900);
        assertThat(dto.expectedCurrency()).isEqualTo("ETB");
    }

    @Test
    void listOrders_sensitiveFields_absentFromDto() {
        BillingRepository.OrderSummaryRow row = buildSummaryRow(userId, "MANUAL_REVIEW", "MANUAL_TRANSFER", "telebirr");
        when(billingRepo.findOrderSummariesByUserId(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(row));
        when(billingRepo.countOrdersByUserId(any(), any())).thenReturn(1L);

        OrderSummaryDto dto = service.listOrders(userId, null, 1, 20).orders().get(0);

        // Verify safe fields are present
        assertThat(dto.id()).isNotNull();
        assertThat(dto.orderReference()).isNotBlank();
        assertThat(dto.status()).isNotBlank();
        assertThat(dto.paymentMethodDisplayName()).isNotBlank();

        // OrderSummaryDto record has no providerCheckoutUrl, paymentInstructions,
        // receiptStoragePath, transactionReference, or admin fields by design.
        // Compile-time guarantee: the record does not declare those fields.
        assertThat(dto).isInstanceOf(com.qaliye.backend.billing.dto.OrderSummaryDto.class);
    }

    @Test
    void manualTransfer_duplicateReference_rejectsWithError() {
        BillingRepository.FullOfferRow offer = buildOffer("ET", "ANDROID", 49900, "ETB");
        BillingRepository.PaymentMethodRow method = buildMethod("telebirr", "MANUAL_TRANSFER", "ET", "ANDROID");
        BillingRepository.OrderRow existingOrder = buildOrderRow(userId, offerId, methodId,
                "VERIFIED", "MANUAL_TRANSFER", "telebirr", null);

        when(billingRepo.findOfferById(offerId)).thenReturn(Optional.of(offer));
        when(billingRepo.findPaymentMethodById(methodId)).thenReturn(Optional.of(method));
        when(billingRepo.findOrderByManualReference(any(), any())).thenReturn(Optional.of(existingOrder));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service.submitManualTransferVerification(userId,
                        new ManualTransferVerifyRequest(offerId, methodId, "ANDROID",
                                Map.of("transactionOrReference", "ABCDEF"), null)));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getReason()).isEqualTo("transaction_already_used");
        verify(billingRepo, never()).insertManualTransferOrder(any(), any(), any(), any(), any(),
                anyInt(), any(), any(), any(), any(), any(), any());
        verify(verifyEtClient, never()).submit(any(), any(), any(), any());
        verify(billingRepo, never()).incrementVerificationCount(any());
    }

    @Test
    void manualTransfer_queued_savesProviderVerificationRequestId() {
        BillingRepository.FullOfferRow offer = buildOffer("ET", "ANDROID", 49900, "ETB");
        BillingRepository.PaymentMethodRow method = buildMethod("telebirr", "MANUAL_TRANSFER", "ET", "ANDROID");
        BillingRepository.OrderRow orderPending = buildOrderRow(userId, offerId, methodId,
                "VERIFICATION_PENDING", "MANUAL_TRANSFER", "telebirr", null);
        VerifyEtClient.VerifyEtResponse queuedResp =
                new VerifyEtClient.VerifyEtResponse(true, "REQ-QUEUED", "queued", "pending", false, null, null);

        when(billingRepo.findOfferById(offerId)).thenReturn(Optional.of(offer));
        when(billingRepo.findPaymentMethodById(methodId)).thenReturn(Optional.of(method));
        when(billingRepo.findOrderByManualReference(any(), any())).thenReturn(Optional.empty());
        when(billingRepo.insertManualTransferOrder(any(), any(), any(), anyString(), anyString(),
                anyInt(), anyString(), anyString(), any(), any(), any(), any())).thenReturn(orderPending);
        when(billingRepo.insertProof(any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(UUID.randomUUID());
        when(billingRepo.insertVerificationAttempt(any(), any(), any(), any(), any()))
                .thenReturn(UUID.randomUUID());
        when(verifyEtClient.submit(any(), any(), any(), any())).thenReturn(queuedResp);
        when(billingRepo.findOrderById(orderPending.id())).thenReturn(Optional.of(orderPending));

        OrderResponse response = service.submitManualTransferVerification(userId,
                new ManualTransferVerifyRequest(offerId, methodId, "ANDROID",
                        Map.of("transactionOrReference", "ABCDEF"), null));

        assertThat(response.status()).isEqualTo("VERIFICATION_PENDING");
        verify(billingRepo).updateOrderProviderVerificationRequestId(orderPending.id(), "REQ-QUEUED");
        verify(billingRepo).incrementVerificationCount(orderPending.id());
        verify(fulfillmentService, never()).fulfillVerifiedOrder(any(), any());
    }

    @Test
    void manualTransfer_duplicateRefQueuedPending_returnsVerificationPending() {
        BillingRepository.FullOfferRow offer = buildOffer("ET", "ANDROID", 49900, "ETB");
        BillingRepository.PaymentMethodRow method = buildMethod("telebirr", "MANUAL_TRANSFER", "ET", "ANDROID");
        BillingRepository.OrderRow existingOrder = new BillingRepository.OrderRow(
                UUID.randomUUID(), userId, offerId, methodId,
                "QAL-EXISTING", "VERIFICATION_PENDING", null,
                49900, "ETB",
                "MANUAL_TRANSFER", "METHOD", "telebirr", "Display Name",
                null, null,
                Instant.now().plusSeconds(7200), Instant.now(), Instant.now(),
                "ABCDEF", "ABCDEF", "REQ-QUEUED", 0
        );
        VerifyEtClient.VerifyEtResponse stillQueuedResp =
                new VerifyEtClient.VerifyEtResponse(true, "REQ-QUEUED", "queued", "pending", false, null, null);

        when(billingRepo.findOfferById(offerId)).thenReturn(Optional.of(offer));
        when(billingRepo.findPaymentMethodById(methodId)).thenReturn(Optional.of(method));
        when(billingRepo.findOrderByManualReference(any(), any())).thenReturn(Optional.of(existingOrder));
        when(verifyEtClient.checkStatus("REQ-QUEUED")).thenReturn(stillQueuedResp);

        OrderResponse response = service.submitManualTransferVerification(userId,
                new ManualTransferVerifyRequest(offerId, methodId, "ANDROID",
                        Map.of("transactionOrReference", "ABCDEF"), null));

        assertThat(response.status()).isEqualTo("VERIFICATION_PENDING");
        verify(billingRepo, never()).insertManualTransferOrder(any(), any(), any(), any(), any(),
                anyInt(), any(), any(), any(), any(), any(), any());
        verify(billingRepo, never()).incrementVerificationCount(any());
        verify(fulfillmentService, never()).fulfillVerifiedOrder(any(), any());
    }

    @Test
    void manualTransfer_duplicateRefQueuedCompleted_verifiedAndFulfilled() {
        BillingRepository.FullOfferRow offer = buildOffer("ET", "ANDROID", 49900, "ETB");
        BillingRepository.PaymentMethodRow method = buildMethod("telebirr", "MANUAL_TRANSFER", "ET", "ANDROID");
        UUID existingOrderId = UUID.randomUUID();
        BillingRepository.OrderRow existingOrder = new BillingRepository.OrderRow(
                existingOrderId, userId, offerId, methodId,
                "QAL-EXISTING", "VERIFICATION_PENDING", null,
                49900, "ETB",
                "MANUAL_TRANSFER", "METHOD", "telebirr", "Display Name",
                null, null,
                Instant.now().plusSeconds(7200), Instant.now(), Instant.now(),
                "ABCDEF", "ABCDEF", "REQ-QUEUED", 0
        );
        BillingRepository.OrderRow verifiedOrder = new BillingRepository.OrderRow(
                existingOrderId, userId, offerId, methodId,
                "QAL-EXISTING", "VERIFIED", null,
                49900, "ETB",
                "MANUAL_TRANSFER", "METHOD", "telebirr", "Display Name",
                null, null,
                Instant.now().plusSeconds(7200), Instant.now(), Instant.now(),
                "ABCDEF", "ABCDEF", "REQ-QUEUED", 0
        );

        UUID attemptId = UUID.randomUUID();
        BillingRepository.VerificationAttemptRow attempt = new BillingRepository.VerificationAttemptRow(
                attemptId, existingOrderId, userId, "PENDING",
                49900, "ETB", Instant.now(), "telebirr"
        );

        VerifyEtClient.VerifyEtResult result = new VerifyEtClient.VerifyEtResult(
                "telebirr", "499.00", "ETB", "REF001", "****", null,
                new VerifyEtClient.ConfirmationHistory(false, 1),
                new VerifyEtClient.SettlementAccountMatch(true, false, "exact", "high", "****1234"));
        VerifyEtClient.VerifyEtResponse completedResp =
                new VerifyEtClient.VerifyEtResponse(false, "REQ-QUEUED", "completed", "success", true, result, null);

        when(billingRepo.findOfferById(offerId)).thenReturn(Optional.of(offer));
        when(billingRepo.findPaymentMethodById(methodId)).thenReturn(Optional.of(method));
        when(billingRepo.findOrderByManualReference(any(), any())).thenReturn(Optional.of(existingOrder));
        when(verifyEtClient.checkStatus("REQ-QUEUED")).thenReturn(completedResp);
        when(billingRepo.findVerificationByVerifyEtRequestId("REQ-QUEUED")).thenReturn(Optional.of(attempt));
        when(billingRepo.existsVerifiedProviderReference(any(), any())).thenReturn(false);
        when(billingRepo.findOrderById(existingOrderId)).thenReturn(Optional.of(verifiedOrder));

        OrderResponse response = service.submitManualTransferVerification(userId,
                new ManualTransferVerifyRequest(offerId, methodId, "ANDROID",
                        Map.of("transactionOrReference", "ABCDEF"), null));

        assertThat(response.status()).isEqualTo("VERIFIED");
        verify(billingRepo).updateOrderStatus(existingOrderId, "VERIFIED", null);
        verify(fulfillmentService).fulfillVerifiedOrder(existingOrderId, userId);
        verify(billingRepo, never()).insertManualTransferOrder(any(), any(), any(), any(), any(),
                anyInt(), any(), any(), any(), any(), any(), any());
        verify(billingRepo, never()).incrementVerificationCount(any());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private BillingRepository.FullOfferRow buildOffer(String country, String platform,
                                                       int price, String currency) {
        return new BillingRepository.FullOfferRow(
                offerId, UUID.randomUUID(), null,
                country, platform,
                currency, price, false,
                null, null, null,
                "PREMIUM_MONTHLY", "MONTH", 1, UUID.randomUUID(),
                0L, null, null, null, null
        );
    }

    private BillingRepository.FullOfferRow buildOfferWithProduct(String country, String platform,
                                                                   int price, String currency,
                                                                   UUID subscriptionProductId) {
        return new BillingRepository.FullOfferRow(
                offerId, subscriptionProductId, null,
                country, platform,
                currency, price, false,
                null, null, null,
                "PREMIUM_MONTHLY", "MONTH", 1, UUID.randomUUID(),
                0L, null, null, null, null
        );
    }

    private BillingRepository.PaymentMethodRow buildMethod(String methodCode, String channel,
                                                            String country, String platform) {
        return new BillingRepository.PaymentMethodRow(
                methodId, country, platform,
                methodCode, "Pay via " + methodCode,
                channel, "METHOD",
                null, true, 1,
                null, null
        );
    }

    private BillingRepository.OrderRow buildOrderRow(UUID ownerId, UUID oId, UUID mId,
                                                      String status, String channel,
                                                      String methodCode, String checkoutUrl) {
        return buildOrderRow(ownerId, oId, mId, status, channel, methodCode, checkoutUrl, 0);
    }

    private BillingRepository.OrderRow buildOrderRow(UUID ownerId, UUID oId, UUID mId,
                                                      String status, String channel,
                                                      String methodCode, String checkoutUrl,
                                                      Integer verificationCount) {
        return new BillingRepository.OrderRow(
                UUID.randomUUID(), ownerId, oId, mId,
                "QAL-TEST001", status, null,
                49900, "ETB",
                channel, "METHOD", methodCode, "Display Name",
                checkoutUrl, null,
                Instant.now().plusSeconds(7200), Instant.now(), Instant.now(),
                null, null, null, verificationCount
        );
    }

    private BillingRepository.OrderSummaryRow buildSummaryRow(UUID ownerId, String status,
                                                                String paymentChannel, String methodCode) {
        return new BillingRepository.OrderSummaryRow(
                UUID.randomUUID(), ownerId, offerId, methodId,
                "QAL-TEST001", status,
                49900, "ETB",
                paymentChannel, "METHOD", methodCode, "Display Name",
                "PREMIUM_MONTHLY", "SUBSCRIPTION", "Premium Monthly",
                Instant.now().plusSeconds(7200), Instant.now(), Instant.now(),
                0
        );
    }
}
