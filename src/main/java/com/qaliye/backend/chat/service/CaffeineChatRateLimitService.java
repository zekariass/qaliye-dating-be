package com.qaliye.backend.chat.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.qaliye.backend.chat.config.ChatProperties;
import com.qaliye.backend.chat.exception.RateLimitedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Caffeine-backed fixed-window rate limiter.
 *
 * <p>Limits are local to the JVM and reset on restart. Suitable for single-instance
 * (MVP) deployments. Before horizontally scaling Spring Boot, replace this bean with
 * a shared implementation such as Redis or PostgreSQL-backed rate limiting.
 *
 * <p>Two limits are enforced per {@code checkSendMessage} call:
 * <ul>
 *   <li>Per-user: {@code CHAT_RATE_LIMIT_USER_PER_MINUTE} sends per minute across all matches.</li>
 *   <li>Per-user-per-match: {@code CHAT_RATE_LIMIT_MATCH_PER_MINUTE} sends per minute for a specific match.</li>
 * </ul>
 *
 * <p>Message body content is never logged here.
 */
@Service
public class CaffeineChatRateLimitService implements ChatRateLimitService {

    private static final Logger log = LoggerFactory.getLogger(CaffeineChatRateLimitService.class);

    /**
     * Immutable snapshot of a fixed window.
     * {@code startMs} is the epoch-ms when the window opened.
     * {@code count} is the number of requests in that window.
     */
    private record Window(long startMs, long count) {}

    private final LoadingCache<String, AtomicReference<Window>> cache;
    private final ChatProperties props;
    private final long windowMs;

    public CaffeineChatRateLimitService(ChatProperties props) {
        this.props = props;
        this.windowMs = props.getRateLimit().getWindowMillis();
        ChatProperties.RateLimit rl = props.getRateLimit();
        this.cache = Caffeine.newBuilder()
                .maximumSize(rl.getCacheMaxSize())
                .expireAfterWrite(rl.getCacheExpireMinutes(), TimeUnit.MINUTES)
                .build(key -> new AtomicReference<>(new Window(0L, 0L)));
    }

    @Override
    public void checkSendMessage(UUID callerId, UUID matchId) {
        if (!props.getRateLimit().isEnabled()) return;
        enforce("chat:send:user:" + callerId,
                props.getRateLimit().getUserPerMinute(),
                "user " + callerId);
        enforce("chat:send:match:" + matchId + ":user:" + callerId,
                props.getRateLimit().getMatchPerMinute(),
                "match " + matchId);
    }

    private void enforce(String key, int limit, String scopeDescription) {
        long count = increment(key);
        if (count > limit) {
            long retryAfterSeconds = remainingWindowSeconds(key);
            log.warn("Rate limit exceeded for scope=[{}] count={} limit={}", scopeDescription, count, limit);
            throw new RateLimitedException(retryAfterSeconds);
        }
    }

    /**
     * Atomically increments the counter for {@code key} within the current window.
     * If the window has expired, a fresh window is started at the current time.
     */
    public long increment(String key) {
        AtomicReference<Window> ref = cache.get(key);
        long now = System.currentTimeMillis();
        while (true) {
            Window current = ref.get();
            Window next;
            if (now - current.startMs() >= windowMs) {
                next = new Window(now, 1L);
            } else {
                next = new Window(current.startMs(), current.count() + 1);
            }
            if (ref.compareAndSet(current, next)) {
                return next.count();
            }
        }
    }

    private long remainingWindowSeconds(String key) {
        AtomicReference<Window> ref = cache.getIfPresent(key);
        if (ref == null) return windowMs / 1000;
        Window w = ref.get();
        long remainingMs = w.startMs() + windowMs - System.currentTimeMillis();
        return Math.max(1L, (remainingMs + 999) / 1000);
    }
}
