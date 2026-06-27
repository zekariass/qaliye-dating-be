package com.qaliye.backend.chat.exception;

public class AccountNotActiveException extends ChatException {
    public AccountNotActiveException() {
        super("ACCOUNT_NOT_ACTIVE", "Your account is not active.", 403);
    }
}
