package com.qaliye.backend.chat.exception;

import com.qaliye.backend.billing.service.CreditService;
import com.qaliye.backend.discovery.exception.ActionLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.UUID;

@RestControllerAdvice(basePackages = "com.qaliye.backend.chat.controller")
@Order(Integer.MIN_VALUE)
public class ChatExceptionHandler {

    @ExceptionHandler(ChatException.class)
    public ResponseEntity<ChatProblemDetail> handleChatException(
            ChatException ex, HttpServletRequest request) {
        ChatProblemDetail body = ChatProblemDetail.of(ex, request.getRequestURI(), UUID.randomUUID().toString());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ex.getStatus());
        if (ex instanceof RateLimitedException rle && rle.getRetryAfterSeconds() > 0) {
            builder = builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(rle.getRetryAfterSeconds()));
        }
        return builder.body(body);
    }

    @ExceptionHandler(CreditService.InsufficientCreditsException.class)
    public ResponseEntity<Map<String, Object>> handleInsufficientCredits(CreditService.InsufficientCreditsException ex) {
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .body(Map.of("error", Map.of("code", "insufficient_credits", "message", "You don't have enough credits for this action.")));
    }

    @ExceptionHandler(ActionLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleActionLimit(ActionLimitExceededException ex) {
        return ResponseEntity.status(ex.getHttpStatus())
                .body(Map.of("error", Map.of(
                        "code", ex.getErrorCode(),
                        "message", ex.getMessage(),
                        "details", Map.of(
                                "action_type", ex.getActionType(),
                                "period_type", ex.getPeriodType())
                )));
    }
}
