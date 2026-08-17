package com.qaliye.backend.discovery.exception;

import com.qaliye.backend.billing.service.CreditService;
import org.postgresql.util.PSQLException;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(basePackages = "com.qaliye.backend.discovery.controller")
@Order(Integer.MIN_VALUE)
public class DiscoveryExceptionHandler {

    @ExceptionHandler(ActionLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleActionLimit(ActionLimitExceededException ex) {
        return ResponseEntity.status(ex.getHttpStatus())
                .body(errorBodyWithDetails(ex.getErrorCode(), ex.getMessage(),
                        Map.of("action_type", ex.getActionType(),
                               "period_type", ex.getPeriodType())));
    }

    @ExceptionHandler(CreditService.InsufficientCreditsException.class)
    public ResponseEntity<Map<String, Object>> handleInsufficientCredits(CreditService.InsufficientCreditsException ex) {
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .body(errorBody("insufficient_credits", "You don't have enough credits for this action."));
    }

    @ExceptionHandler(DiscoveryException.class)
    public ResponseEntity<Map<String, Object>> handleDiscovery(DiscoveryException ex) {
        return ResponseEntity.status(ex.getHttpStatus())
                .body(errorBody(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(DataIntegrityViolationException ex) {
        Throwable cause = ex.getCause();
        String psqlMsg = cause instanceof PSQLException p ? p.getMessage() : "";
        if (psqlMsg != null && psqlMsg.contains("unique_active_discovery_action_per_pair")) {
            return ResponseEntity.status(409)
                    .body(errorBody("DUPLICATE_ACTIVE_ACTION", "An active action already exists for this target."));
        }
        if (psqlMsg != null && psqlMsg.contains("unique_discovery_client_action")) {
            return ResponseEntity.status(409)
                    .body(errorBody("DUPLICATE_ACTIVE_ACTION", "An active action already exists for this target."));
        }
        return ResponseEntity.status(409)
                .body(errorBody("CONFLICT", "A data conflict occurred."));
    }

    private static Map<String, Object> errorBody(String code, String message) {
        return Map.of("error", Map.of("code", code, "message", message));
    }

    private static Map<String, Object> errorBodyWithDetails(String code, String message, Map<String, Object> details) {
        return Map.of("error", Map.of("code", code, "message", message, "details", details));
    }
}
