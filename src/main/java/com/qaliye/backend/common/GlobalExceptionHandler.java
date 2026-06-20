package com.qaliye.backend.common;

import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(403)
                .body(new ApiError("forbidden", ex.getMessage(), 403));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest()
                .body(new ApiError("validation_error", message, 400));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof PSQLException psql
                && psql.getMessage() != null
                && psql.getMessage().contains("Age Compliance Violation")) {
            return ResponseEntity.badRequest()
                    .body(new ApiError("age_violation", "User must be at least 18 years old", 400));
        }
        return ResponseEntity.status(409)
                .body(new ApiError("conflict", "Conflict", 409));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException ex) {
        int code = ex.getStatusCode().value();
        String error = code == 400 ? "validation_error" : code == 404 ? "not_found" : "error";
        return ResponseEntity.status(code)
                .body(new ApiError(error, ex.getReason() != null ? ex.getReason() : ex.getMessage(), code));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception ex) {
        return ResponseEntity.status(500)
                .body(new ApiError("internal_error", "An unexpected error occurred", 500));
    }
}
