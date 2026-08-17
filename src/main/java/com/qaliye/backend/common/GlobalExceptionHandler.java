package com.qaliye.backend.common;

import com.qaliye.backend.billing.service.CreditService;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        return error(403, "FORBIDDEN", "You do not have permission to perform this action.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return error(400, "VALIDATION_ERROR", message);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(DataIntegrityViolationException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof PSQLException psql
                && psql.getMessage() != null
                && psql.getMessage().contains("Age Compliance Violation")) {
            return error(400, "AGE_VIOLATION", "User must be at least 18 years old.");
        }
        return error(409, "CONFLICT", "A data conflict occurred.");
    }

    @ExceptionHandler(CreditService.InsufficientCreditsException.class)
    public ResponseEntity<Map<String, Object>> handleInsufficientCredits(CreditService.InsufficientCreditsException ex) {
        return error(402, "insufficient_credits", "You don't have enough credits for this action.");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex) {
        int status = ex.getStatusCode().value();
        String reason = ex.getReason() != null ? ex.getReason() : ex.getMessage();
        String code = resolveCode(status, reason);
        return error(status, code, reason);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class)
                .error("Unhandled exception in controller", ex);
        return error(500, "INTERNAL_ERROR", "An unexpected error occurred.");
    }

    private static String resolveCode(int status, String reason) {
        if (reason == null) return "ERROR";
        if ("PHOTO_LIMIT_EXCEEDED".equals(reason))         return "PHOTO_LIMIT_EXCEEDED";
        if ("PHOTO_NOT_FOUND".equals(reason))               return "PHOTO_NOT_FOUND";
        if ("INVALID_PRIMARY_PHOTO".equals(reason))         return "INVALID_PRIMARY_PHOTO";
        if ("NO_QUALIFIED_PRIMARY_PHOTO".equals(reason))    return "NO_QUALIFIED_PRIMARY_PHOTO";
        if ("CANNOT_DELETE_ONLY_PHOTO".equals(reason))      return "CANNOT_DELETE_ONLY_PHOTO";
        if ("NOT_FOUND".equals(reason))                     return "NOT_FOUND";
        if ("UNAUTHORIZED".equals(reason))                  return "UNAUTHORIZED";
        if (status == 422 && isPhotoRejectionMessage(reason)) return "PHOTO_REJECTED";
        if (status == 400) return "VALIDATION_ERROR";
        if (status == 401) return "UNAUTHORIZED";
        if (status == 403) return "FORBIDDEN";
        if (status == 404) return "NOT_FOUND";
        if (status == 409) return "CONFLICT";
        if (status == 422) return "UNPROCESSABLE";
        if (status == 429) return "RATE_LIMITED";
        return "ERROR";
    }

    private static boolean isPhotoRejectionMessage(String reason) {
        return reason.startsWith("The profile photo should have")
                || reason.startsWith("The photo is too dark")
                || reason.startsWith("The photo is too blurry")
                || reason.startsWith("Your face")
                || reason.startsWith("This photo could not be approved")
                || reason.startsWith("This photo could not");
    }

    static ResponseEntity<Map<String, Object>> error(int status, String code, String message) {
        return ResponseEntity.status(status)
                .body(Map.of("error", Map.of("code", code, "message", message)));
    }
}
