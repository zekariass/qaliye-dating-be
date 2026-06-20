package com.qaliye.backend.payments;

import com.qaliye.backend.common.CallerUtils;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/transactions")
public class AdminPaymentController {

    private final PaymentService paymentService;

    public AdminPaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listTransactions(
            @RequestParam(defaultValue = "MANUAL_REVIEW") String status,
            @RequestParam(defaultValue = "CHAPA,TELEBIRR,CBE_BIRR,BANK_TRANSFER") String provider,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        UUID callerId = CallerUtils.callerId();
        Map<String, Object> result = paymentService.listTransactions(
                callerId, status, provider, page, pageSize);
        return ResponseEntity.ok(result);
    }

    @PatchMapping("/{transactionId}")
    public ResponseEntity<Map<String, Object>> reviewTransaction(
            @PathVariable UUID transactionId,
            @Valid @RequestBody AdminTransactionReviewRequest request) {
        UUID callerId = CallerUtils.callerId();
        Map<String, Object> result = paymentService.reviewManualTransaction(
                callerId, transactionId, request);
        return ResponseEntity.ok(result);
    }
}
