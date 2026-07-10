package com.qaliye.backend.billing.exception;

import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice(basePackages = "com.qaliye.backend.billing.controller")
@Order(Integer.MIN_VALUE)
public class BillingExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(Map.of("error", Map.of(
                        "code", ex.getReason() != null ? ex.getReason() : "BILLING_ERROR",
                        "message", ex.getReason() != null ? ex.getReason() : "An error occurred"
                )));
    }
}
