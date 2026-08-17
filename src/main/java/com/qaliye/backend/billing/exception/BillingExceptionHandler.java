package com.qaliye.backend.billing.exception;

import com.qaliye.backend.billing.service.CreditService;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice(basePackages = "com.qaliye.backend.billing.controller")
@Order(Integer.MIN_VALUE)
public class BillingExceptionHandler {

    @ExceptionHandler(CreditService.InsufficientCreditsException.class)
    public ResponseEntity<Map<String, Object>> handleInsufficientCredits(CreditService.InsufficientCreditsException ex) {
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .body(Map.of("error", Map.of("code", "insufficient_credits", "message", "You don't have enough credits for this action.")));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(Map.of("error", Map.of(
                        "code", ex.getReason() != null ? ex.getReason() : "BILLING_ERROR",
                        "message", ex.getReason() != null ? ex.getReason() : "An error occurred"
                )));
    }
}
