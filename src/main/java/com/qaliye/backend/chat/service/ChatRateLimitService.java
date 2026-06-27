package com.qaliye.backend.chat.service;

import java.util.UUID;

public interface ChatRateLimitService {

    /**
     * Checks whether the caller is allowed to send a message in the given match.
     * Throws {@link com.qaliye.backend.chat.exception.RateLimitedException} (HTTP 429)
     * when a limit is exceeded.
     * Must only be called after authentication and match authorization have succeeded.
     * Idempotent retries that return an already-persisted message must NOT call this method.
     */
    void checkSendMessage(UUID callerId, UUID matchId);
}
