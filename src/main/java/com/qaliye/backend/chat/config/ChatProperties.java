package com.qaliye.backend.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "chat")
public class ChatProperties {

    private Outbox outbox = new Outbox();
    private RateLimit rateLimit = new RateLimit();
    private Cursor cursor = new Cursor();

    public Outbox getOutbox() { return outbox; }
    public void setOutbox(Outbox outbox) { this.outbox = outbox; }

    public RateLimit getRateLimit() { return rateLimit; }
    public void setRateLimit(RateLimit rateLimit) { this.rateLimit = rateLimit; }

    public Cursor getCursor() { return cursor; }
    public void setCursor(Cursor cursor) { this.cursor = cursor; }

    public static class Outbox {
        private int batchSize = 100;
        private long pollIntervalMs = 500;
        private int leaseSeconds = 60;
        private int maxAttempts = 20;
        private int maxBackoffSeconds = 300;

        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

        public long getPollIntervalMs() { return pollIntervalMs; }
        public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }

        public int getLeaseSeconds() { return leaseSeconds; }
        public void setLeaseSeconds(int leaseSeconds) { this.leaseSeconds = leaseSeconds; }

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

        public int getMaxBackoffSeconds() { return maxBackoffSeconds; }
        public void setMaxBackoffSeconds(int maxBackoffSeconds) { this.maxBackoffSeconds = maxBackoffSeconds; }
    }

    public static class RateLimit {
        private boolean enabled = true;
        private int userPerMinute = 30;
        private int matchPerMinute = 12;
        private int cacheMaxSize = 100_000;
        private int cacheExpireMinutes = 5;
        private long windowMillis = 60_000L;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getUserPerMinute() { return userPerMinute; }
        public void setUserPerMinute(int userPerMinute) { this.userPerMinute = userPerMinute; }

        public int getMatchPerMinute() { return matchPerMinute; }
        public void setMatchPerMinute(int matchPerMinute) { this.matchPerMinute = matchPerMinute; }

        public int getCacheMaxSize() { return cacheMaxSize; }
        public void setCacheMaxSize(int cacheMaxSize) { this.cacheMaxSize = cacheMaxSize; }

        public int getCacheExpireMinutes() { return cacheExpireMinutes; }
        public void setCacheExpireMinutes(int cacheExpireMinutes) { this.cacheExpireMinutes = cacheExpireMinutes; }

        public long getWindowMillis() { return windowMillis; }
        public void setWindowMillis(long windowMillis) { this.windowMillis = windowMillis; }
    }

    public static class Cursor {
        private String hmacSecret = "change-me-in-production";

        public String getHmacSecret() { return hmacSecret; }
        public void setHmacSecret(String hmacSecret) { this.hmacSecret = hmacSecret; }
    }
}
