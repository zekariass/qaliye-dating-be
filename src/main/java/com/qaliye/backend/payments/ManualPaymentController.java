package com.qaliye.backend.payments;

import com.qaliye.backend.common.CallerUtils;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
public class ManualPaymentController {

    private final PaymentService paymentService;

    public ManualPaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/manual")
    public ResponseEntity<Map<String, Object>> submitManual(
            @Valid @RequestBody ManualPaymentRequest request) {
        UUID callerId = CallerUtils.callerId();
        UUID transactionId = paymentService.submitManualPayment(callerId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "transaction_id", transactionId,
                "status", "MANUAL_REVIEW"
        ));
    }
}
