package com.qaliye.backend.support.exception;

import org.postgresql.util.PSQLException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class SupportRpcException {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SupportRpcException.class);

    private SupportRpcException() {}

    public static ResponseStatusException translate(Exception ex) {
        String msg = findPsqlMessage(ex);
        if (msg != null) {
            if (msg.contains("Idempotency conflict")) {
                return new ResponseStatusException(HttpStatus.CONFLICT, msg);
            }
            if (msg.contains("not found") || msg.contains("not provisioned")) {
                return new ResponseStatusException(HttpStatus.NOT_FOUND, msg);
            }
            if (msg.contains("does not exist")) {
                return new ResponseStatusException(HttpStatus.NOT_FOUND, msg);
            }
            if (msg.contains("ADMIN or MODERATOR role")) {
                return new ResponseStatusException(HttpStatus.FORBIDDEN, msg);
            }
            if (msg.contains("idle conversation")
                    || msg.contains("Conversation is closed")
                    || msg.contains("Only a closed conversation")) {
                return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, msg);
            }
            if (msg.contains("Staff cannot send the first public message")) {
                return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, msg);
            }
            if (msg.contains("must contain")
                    || msg.contains("may contain at most")
                    || msg.contains("exceeds")
                    || msg.contains("priority")
                    || msg.contains("required")
                    || msg.contains("non-negative")
                    || msg.contains("must be a JSON")
                    || msg.contains("must be an integer")) {
                return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
            }
            if (msg.contains("violates check constraint")
                    || msg.contains("violates unique constraint")
                    || msg.contains("duplicate key")) {
                log.error("DB constraint violation in support RPC: {}", msg, ex);
                return new ResponseStatusException(HttpStatus.CONFLICT, msg);
            }
        }
        log.error("Untranslated support RPC exception", ex);
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred");
    }

    private static String findPsqlMessage(Throwable ex) {
        Throwable t = ex;
        while (t != null) {
            if (t instanceof PSQLException psql) {
                return psql.getMessage();
            }
            t = t.getCause();
        }
        return null;
    }
}
