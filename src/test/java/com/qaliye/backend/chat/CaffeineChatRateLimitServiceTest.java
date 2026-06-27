package com.qaliye.backend.chat;

import com.qaliye.backend.chat.config.ChatProperties;
import com.qaliye.backend.chat.exception.RateLimitedException;
import com.qaliye.backend.chat.service.CaffeineChatRateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class CaffeineChatRateLimitServiceTest {

    private static final int USER_LIMIT  = 5;
    private static final int MATCH_LIMIT = 3;
    private static final long WINDOW_MS  = 200L;

    private CaffeineChatRateLimitService service;
    private ChatProperties props;

    UUID userId  = UUID.randomUUID();
    UUID matchId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        props = buildProps(true, USER_LIMIT, MATCH_LIMIT, WINDOW_MS);
        service = new CaffeineChatRateLimitService(props);
    }

    // -----------------------------------------------------------------------
    // Allowed below limit
    // -----------------------------------------------------------------------

    @Test
    void requestsBelowUserLimit_areAllowed() {
        for (int i = 0; i < USER_LIMIT; i++) {
            assertThatNoException().isThrownBy(() -> service.checkSendMessage(userId, matchId));
        }
    }

    @Test
    void requestsBelowMatchLimit_areAllowed() {
        UUID localMatch = UUID.randomUUID();
        for (int i = 0; i < MATCH_LIMIT; i++) {
            assertThatNoException()
                    .isThrownBy(() -> service.checkSendMessage(userId, localMatch));
        }
    }

    // -----------------------------------------------------------------------
    // Rejection after limit
    // -----------------------------------------------------------------------

    @Test
    void exceedingUserLimit_throwsRateLimited() {
        for (int i = 0; i < USER_LIMIT; i++) {
            service.checkSendMessage(userId, matchId);
        }
        assertThatThrownBy(() -> service.checkSendMessage(userId, matchId))
                .isInstanceOf(RateLimitedException.class)
                .extracting(e -> ((RateLimitedException) e).getRetryAfterSeconds())
                .satisfies(s -> assertThat((long) s).isGreaterThan(0));
    }

    @Test
    void exceedingMatchLimit_throwsRateLimited() {
        UUID localMatch = UUID.randomUUID();
        for (int i = 0; i < MATCH_LIMIT; i++) {
            service.checkSendMessage(userId, localMatch);
        }
        assertThatThrownBy(() -> service.checkSendMessage(userId, localMatch))
                .isInstanceOf(RateLimitedException.class);
    }

    // -----------------------------------------------------------------------
    // Separate counts for different users
    // -----------------------------------------------------------------------

    @Test
    void differentUsers_haveIndependentCounters() {
        UUID otherUser = UUID.randomUUID();
        for (int i = 0; i < USER_LIMIT; i++) {
            service.checkSendMessage(userId, matchId);
        }
        // otherUser's counter starts fresh
        assertThatNoException()
                .isThrownBy(() -> service.checkSendMessage(otherUser, matchId));
    }

    // -----------------------------------------------------------------------
    // Separate counts for different matches
    // -----------------------------------------------------------------------

    @Test
    void differentMatches_haveIndependentCounters() {
        UUID matchA = UUID.randomUUID();
        UUID matchB = UUID.randomUUID();
        for (int i = 0; i < MATCH_LIMIT; i++) {
            service.checkSendMessage(userId, matchA);
        }
        // matchB counter starts fresh for the same user
        assertThatNoException()
                .isThrownBy(() -> service.checkSendMessage(userId, matchB));
    }

    // -----------------------------------------------------------------------
    // Window expiry / reset
    // -----------------------------------------------------------------------

    @Test
    void windowExpiry_resetsCounter() throws InterruptedException {
        for (int i = 0; i < USER_LIMIT; i++) {
            service.checkSendMessage(userId, matchId);
        }
        // Limit is reached
        assertThatThrownBy(() -> service.checkSendMessage(userId, matchId))
                .isInstanceOf(RateLimitedException.class);

        // Wait for the window to expire
        Thread.sleep(WINDOW_MS + 50);

        // Counter should be reset; request should be allowed again
        assertThatNoException().isThrownBy(() -> service.checkSendMessage(userId, matchId));
    }

    // -----------------------------------------------------------------------
    // Disabled flag bypasses all checks
    // -----------------------------------------------------------------------

    @Test
    void rateLimitDisabled_allowsUnlimitedRequests() {
        ChatProperties disabledProps = buildProps(false, 1, 1, WINDOW_MS);
        CaffeineChatRateLimitService disabledService =
                new CaffeineChatRateLimitService(disabledProps);

        for (int i = 0; i < 100; i++) {
            assertThatNoException()
                    .isThrownBy(() -> disabledService.checkSendMessage(userId, matchId));
        }
    }

    // -----------------------------------------------------------------------
    // Retry-After is positive when rejecting
    // -----------------------------------------------------------------------

    @Test
    void rateLimited_retryAfterIsPositive() {
        for (int i = 0; i < USER_LIMIT; i++) service.checkSendMessage(userId, matchId);
        try {
            service.checkSendMessage(userId, matchId);
            fail("Expected RateLimitedException");
        } catch (RateLimitedException ex) {
            assertThat(ex.getRetryAfterSeconds()).isGreaterThan(0);
        }
    }

    // -----------------------------------------------------------------------
    // Increment is atomic / thread-safe (basic smoke)
    // -----------------------------------------------------------------------

    @Test
    void increment_isSafeUnderConcurrentAccess() throws InterruptedException {
        String key = "test:concurrent:" + UUID.randomUUID();
        int threads = 20;
        int callsPerThread = 50;
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            workers[t] = new Thread(() -> {
                for (int i = 0; i < callsPerThread; i++) service.increment(key);
            });
        }
        for (Thread w : workers) w.start();
        for (Thread w : workers) w.join();

        long total = service.increment(key);
        assertThat(total).isEqualTo((long) threads * callsPerThread + 1);
    }

    // -----------------------------------------------------------------------
    // Idempotent retries: calling checkSendMessage is NOT how idempotency works;
    // the guard is in MessageCommandService (rate limiter is skipped for retries).
    // This test documents that the rate limiter DOES count calls if invoked,
    // confirming MessageCommandService must bypass it for duplicates.
    // -----------------------------------------------------------------------

    @Test
    void directCalls_alwaysConsumeQuota() {
        for (int i = 0; i < USER_LIMIT; i++) service.checkSendMessage(userId, matchId);
        // A second identical call still counts — callers must guard themselves
        assertThatThrownBy(() -> service.checkSendMessage(userId, matchId))
                .isInstanceOf(RateLimitedException.class);
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private static ChatProperties buildProps(boolean enabled, int userPerMin, int matchPerMin, long windowMs) {
        ChatProperties p = new ChatProperties();
        p.getRateLimit().setEnabled(enabled);
        p.getRateLimit().setUserPerMinute(userPerMin);
        p.getRateLimit().setMatchPerMinute(matchPerMin);
        p.getRateLimit().setCacheMaxSize(10_000);
        p.getRateLimit().setCacheExpireMinutes(1);
        p.getRateLimit().setWindowMillis(windowMs);
        return p;
    }
}
