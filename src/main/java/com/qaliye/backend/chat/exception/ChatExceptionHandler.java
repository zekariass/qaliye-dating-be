package com.qaliye.backend.chat.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
}
