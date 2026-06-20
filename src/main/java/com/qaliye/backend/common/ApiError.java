package com.qaliye.backend.common;

public record ApiError(String error, String message, int status) {
}
