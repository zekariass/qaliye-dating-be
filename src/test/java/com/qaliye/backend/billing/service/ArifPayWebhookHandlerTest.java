package com.qaliye.backend.billing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaliye.backend.billing.provider.ArifPayGatewayClient;
import com.qaliye.backend.billing.repository.BillingRepository;
import com.qaliye.backend.billing.repository.PromotionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ArifPayWebhookHandlerTest {

    @Mock BillingRepository billingRepo;
    @Mock FulfillmentService fulfillmentService;
    @Mock PromotionRepository promotionRepo;
    @Mock ArifPayGatewayClient arifPayClient;

    ArifPayWebhookHandler handler;
    ObjectMapper objectMapper = new ObjectMapper();

    UUID orderId  = UUID.randomUUID();
    UUID userId   = UUID.randomUUID();
    UUID offerId  = UUID.randomUUID();
    UUID methodId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        handler = new ArifPayWebhookHandler(billingRepo, fulfillmentService, promotionRepo,
                objectMapper, arifPayClient);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private BillingRepository.OrderRow buildOrder(String status, int amount) {
        return new BillingRepository.OrderRow(
                orderId, userId, offerId, methodId,
                "QAL-TEST001", status, null,
                amount, "ETB",
                "ONLINE_PAYMENT", "METHOD", "arifpay", "ArifPay",
                "https://checkout.arifpay.net/sess-abc", "sess-abc",
                Instant.now().plusSeconds(7200), Instant.now(), Instant.now(),
                null, null, null, 0
        );
    }

    private byte[] payload(String nonce, String sessionId, String status) throws Exception {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("nonce", nonce);
        map.put("sessionId", sessionId);
        map.put("uuid", sessionId);
        map.put("transactionStatus", status);
        map.put("totalAmount", 499.00);
        map.put("transaction", java.util.Map.of(
                "transactionId", "TXN-" + nonce,
                "transactionStatus", status
        ));
        return objectMapper.writeValueAsBytes(map);
    }

    // ── tests ──────────────────────────────────────────────────────────────────

    @Test
    void handle_successWebhook_verifiesAndFulfills() throws Exception {
        BillingRepository.OrderRow order = buildOrder("AWAITING_PAYMENT", 49900);

        when(billingRepo.logEvent(eq("ARIFPAY"), anyString(), any(), any(), any()))
                .thenReturn(Optional.of(UUID.randomUUID()));
        when(billingRepo.findOrderByReference("QAL-TEST001")).thenReturn(Optional.of(order));
        when(arifPayClient.verifySession("sess-abc")).thenReturn(
                new ArifPayGatewayClient.VerifyResult("SUCCESS", "QAL-TEST001", "499.00", "ETB", "TXN-1")
        );

        handler.handle(payload("QAL-TEST001", "sess-abc", "SUCCESS"));

        verify(billingRepo).updateOrderStatus(orderId, "VERIFIED", "ArifPay payment verified");
        verify(fulfillmentService).fulfillVerifiedOrder(orderId, userId);
        verify(promotionRepo, never()).cancelRedemptionByOrderId(any(), any());
    }

    @Test
    void handle_duplicateEvent_ignored() throws Exception {
        when(billingRepo.logEvent(eq("ARIFPAY"), anyString(), any(), any(), any()))
                .thenReturn(Optional.empty());

        handler.handle(payload("QAL-TEST001", "sess-dup", "SUCCESS"));

        verify(billingRepo, never()).findOrderByReference(any());
        verify(fulfillmentService, never()).fulfillVerifiedOrder(any(), any());
    }

    @Test
    void handle_orderNotFound_logsAndReturns() throws Exception {
        when(billingRepo.logEvent(eq("ARIFPAY"), anyString(), any(), any(), any()))
                .thenReturn(Optional.of(UUID.randomUUID()));
        when(billingRepo.findOrderByReference("QAL-MISSING")).thenReturn(Optional.empty());

        handler.handle(payload("QAL-MISSING", "sess-xyz", "SUCCESS"));

        verify(billingRepo, never()).updateOrderStatus(any(), any(), any());
        verify(fulfillmentService, never()).fulfillVerifiedOrder(any(), any());
    }

    @Test
    void handle_terminalOrder_skipped() throws Exception {
        BillingRepository.OrderRow order = buildOrder("VERIFIED", 49900);

        when(billingRepo.logEvent(eq("ARIFPAY"), anyString(), any(), any(), any()))
                .thenReturn(Optional.of(UUID.randomUUID()));
        when(billingRepo.findOrderByReference("QAL-TEST001")).thenReturn(Optional.of(order));

        handler.handle(payload("QAL-TEST001", "sess-abc", "SUCCESS"));

        verify(arifPayClient, never()).verifySession(any());
        verify(billingRepo, never()).updateOrderStatus(any(), any(), any());
    }

    @Test
    void handle_successWebhook_amountMismatch_setsManualReview() throws Exception {
        BillingRepository.OrderRow order = buildOrder("AWAITING_PAYMENT", 49900);

        when(billingRepo.logEvent(eq("ARIFPAY"), anyString(), any(), any(), any()))
                .thenReturn(Optional.of(UUID.randomUUID()));
        when(billingRepo.findOrderByReference("QAL-TEST001")).thenReturn(Optional.of(order));
        when(arifPayClient.verifySession("sess-abc")).thenReturn(
                new ArifPayGatewayClient.VerifyResult("SUCCESS", "QAL-TEST001", "199.00", "ETB", "TXN-2")
        );

        handler.handle(payload("QAL-TEST001", "sess-abc", "SUCCESS"));

        verify(billingRepo).updateOrderStatus(eq(orderId), eq("MANUAL_REVIEW"), contains("amount mismatch"));
        verify(fulfillmentService, never()).fulfillVerifiedOrder(any(), any());
    }

    @Test
    void handle_successWebhook_verifyReturnsFailed_rejects() throws Exception {
        BillingRepository.OrderRow order = buildOrder("AWAITING_PAYMENT", 49900);

        when(billingRepo.logEvent(eq("ARIFPAY"), anyString(), any(), any(), any()))
                .thenReturn(Optional.of(UUID.randomUUID()));
        when(billingRepo.findOrderByReference("QAL-TEST001")).thenReturn(Optional.of(order));
        when(arifPayClient.verifySession("sess-abc")).thenReturn(
                new ArifPayGatewayClient.VerifyResult("FAILED", null, null, null, null)
        );

        handler.handle(payload("QAL-TEST001", "sess-abc", "SUCCESS"));

        verify(billingRepo).updateOrderStatus(eq(orderId), eq("REJECTED"), any());
        verify(promotionRepo).cancelRedemptionByOrderId(orderId, "payment_failed");
        verify(fulfillmentService, never()).fulfillVerifiedOrder(any(), any());
    }

    @Test
    void handle_failedWebhook_rejectsOrder() throws Exception {
        BillingRepository.OrderRow order = buildOrder("AWAITING_PAYMENT", 49900);

        when(billingRepo.logEvent(eq("ARIFPAY"), anyString(), any(), any(), any()))
                .thenReturn(Optional.of(UUID.randomUUID()));
        when(billingRepo.findOrderByReference("QAL-TEST001")).thenReturn(Optional.of(order));

        handler.handle(payload("QAL-TEST001", "sess-abc", "FAILED"));

        verify(billingRepo).updateOrderStatus(eq(orderId), eq("REJECTED"), any());
        verify(promotionRepo).cancelRedemptionByOrderId(orderId, "payment_failed");
        verify(arifPayClient, never()).verifySession(any());
    }

    @Test
    void handle_cancelledWebhook_rejectsOrder() throws Exception {
        BillingRepository.OrderRow order = buildOrder("AWAITING_PAYMENT", 49900);

        when(billingRepo.logEvent(eq("ARIFPAY"), anyString(), any(), any(), any()))
                .thenReturn(Optional.of(UUID.randomUUID()));
        when(billingRepo.findOrderByReference("QAL-TEST001")).thenReturn(Optional.of(order));

        handler.handle(payload("QAL-TEST001", "sess-abc", "CANCELLED"));

        verify(billingRepo).updateOrderStatus(eq(orderId), eq("REJECTED"), any());
        verify(promotionRepo).cancelRedemptionByOrderId(orderId, "payment_cancelled");
    }

    @Test
    void handle_missingNonceAndSessionId_logsAndReturns() throws Exception {
        byte[] badBody = objectMapper.writeValueAsBytes(java.util.Map.of("status", "SUCCESS"));

        handler.handle(badBody);

        verify(billingRepo, never()).logEvent(any(), any(), any(), any(), any());
        verify(billingRepo, never()).findOrderByReference(any());
    }

    @Test
    void handle_usesStoredSessionIdForVerify_whenWebhookOmitsIt() throws Exception {
        BillingRepository.OrderRow order = new BillingRepository.OrderRow(
                orderId, userId, offerId, methodId,
                "QAL-TEST002", "AWAITING_PAYMENT", null,
                49900, "ETB",
                "ONLINE_PAYMENT", "METHOD", "arifpay", "ArifPay",
                "https://checkout.arifpay.net/stored-sess", "stored-sess",
                Instant.now().plusSeconds(7200), Instant.now(), Instant.now(),
                null, null, null, 0
        );

        byte[] bodyWithoutSession = objectMapper.writeValueAsBytes(
                java.util.Map.of("nonce", "QAL-TEST002", "status", "SUCCESS")
        );

        when(billingRepo.logEvent(eq("ARIFPAY"), anyString(), any(), any(), any()))
                .thenReturn(Optional.of(UUID.randomUUID()));
        when(billingRepo.findOrderByReference("QAL-TEST002")).thenReturn(Optional.of(order));
        when(arifPayClient.verifySession("stored-sess")).thenReturn(
                new ArifPayGatewayClient.VerifyResult("SUCCESS", "QAL-TEST002", "499.00", "ETB", "TXN-3")
        );

        handler.handle(bodyWithoutSession);

        verify(arifPayClient).verifySession("stored-sess");
        verify(fulfillmentService).fulfillVerifiedOrder(orderId, userId);
    }
}
