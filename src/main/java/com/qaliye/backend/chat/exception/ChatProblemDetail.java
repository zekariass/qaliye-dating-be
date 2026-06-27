package com.qaliye.backend.chat.exception;

public record ChatProblemDetail(
        String type,
        String title,
        int status,
        String code,
        String detail,
        String instance,
        String requestId
) {
    private static final String BASE_TYPE = "https://api.qaliye.com/problems/";

    public static ChatProblemDetail of(ChatException ex, String instance, String requestId) {
        return new ChatProblemDetail(
                BASE_TYPE + ex.getCode(),
                titleFor(ex.getCode()),
                ex.getStatus(),
                ex.getCode(),
                ex.getMessage(),
                instance,
                requestId
        );
    }

    private static String titleFor(String code) {
        return switch (code) {
            case "UNAUTHORIZED"             -> "Unauthorized";
            case "ACCOUNT_NOT_ACTIVE"       -> "Account is not active";
            case "MATCH_NOT_FOUND"          -> "Match not found";
            case "MATCH_ACCESS_DENIED"      -> "Match access denied";
            case "MATCH_NOT_ACTIVE"         -> "Match is not active";
            case "USER_BLOCKED"             -> "User is blocked";
            case "MESSAGE_NOT_FOUND"        -> "Message not found";
            case "INVALID_CURSOR"           -> "Invalid cursor";
            case "INVALID_RECEIPT_SEQUENCE" -> "Invalid receipt sequence";
            case "INVALID_MESSAGE"          -> "Invalid message";
            case "IDEMPOTENCY_CONFLICT"     -> "Idempotency conflict";
            case "RATE_LIMITED"             -> "Rate limit exceeded";
            default                         -> "Error";
        };
    }
}
