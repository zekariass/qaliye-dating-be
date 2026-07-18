package com.qaliye.backend.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "chat")
public class ChatProperties {

    private Outbox outbox = new Outbox();
    private RateLimit rateLimit = new RateLimit();
    private Cursor cursor = new Cursor();
    private Attachment attachment = new Attachment();

    public Outbox getOutbox() { return outbox; }
    public void setOutbox(Outbox outbox) { this.outbox = outbox; }

    public RateLimit getRateLimit() { return rateLimit; }
    public void setRateLimit(RateLimit rateLimit) { this.rateLimit = rateLimit; }

    public Cursor getCursor() { return cursor; }
    public void setCursor(Cursor cursor) { this.cursor = cursor; }

    public Attachment getAttachment() { return attachment; }
    public void setAttachment(Attachment attachment) { this.attachment = attachment; }

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

    public static class Attachment {
        private String bucket = "chat-attachments";
        private int signedUrlTtlSeconds = 300;
        private long imageMaxFileSizeBytes = 26214400L;
        private long voiceMaxFileSizeBytes = 26214400L;
        private int voiceMaxDurationSeconds = 300;
        private int maxImageAttachments = 5;
        private int maxVoiceAttachments = 1;
        private int maxTotalAttachments = 5;
        private List<String> allowedImageContentTypes = List.of(
                "image/jpeg",
                "image/png",
                "image/webp",
                "image/gif",
                "image/bmp",
                "image/heic",
                "image/heif",
                "image/avif",
                "image/tiff"
        );
        private List<String> allowedVoiceContentTypes = List.of(
                "audio/m4a",
                "audio/mp4",
                "audio/aac",
                "audio/mpeg",
                "audio/x-m4a",
                "audio/mp3",
                "audio/ogg",
                "audio/wav",
                "audio/x-wav",
                "audio/webm",
                "audio/flac",
                "audio/3gpp",
                "audio/amr"
        );

        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }

        public int getSignedUrlTtlSeconds() { return signedUrlTtlSeconds; }
        public void setSignedUrlTtlSeconds(int signedUrlTtlSeconds) { this.signedUrlTtlSeconds = signedUrlTtlSeconds; }

        public long getImageMaxFileSizeBytes() { return imageMaxFileSizeBytes; }
        public void setImageMaxFileSizeBytes(long imageMaxFileSizeBytes) { this.imageMaxFileSizeBytes = imageMaxFileSizeBytes; }

        public long getVoiceMaxFileSizeBytes() { return voiceMaxFileSizeBytes; }
        public void setVoiceMaxFileSizeBytes(long voiceMaxFileSizeBytes) { this.voiceMaxFileSizeBytes = voiceMaxFileSizeBytes; }

        public int getVoiceMaxDurationSeconds() { return voiceMaxDurationSeconds; }
        public void setVoiceMaxDurationSeconds(int voiceMaxDurationSeconds) { this.voiceMaxDurationSeconds = voiceMaxDurationSeconds; }

        public int getMaxImageAttachments() { return maxImageAttachments; }
        public void setMaxImageAttachments(int maxImageAttachments) { this.maxImageAttachments = maxImageAttachments; }

        public int getMaxVoiceAttachments() { return maxVoiceAttachments; }
        public void setMaxVoiceAttachments(int maxVoiceAttachments) { this.maxVoiceAttachments = maxVoiceAttachments; }

        public int getMaxTotalAttachments() { return maxTotalAttachments; }
        public void setMaxTotalAttachments(int maxTotalAttachments) { this.maxTotalAttachments = maxTotalAttachments; }

        public List<String> getAllowedImageContentTypes() { return allowedImageContentTypes; }
        public void setAllowedImageContentTypes(List<String> allowedImageContentTypes) { this.allowedImageContentTypes = allowedImageContentTypes; }

        public List<String> getAllowedVoiceContentTypes() { return allowedVoiceContentTypes; }
        public void setAllowedVoiceContentTypes(List<String> allowedVoiceContentTypes) { this.allowedVoiceContentTypes = allowedVoiceContentTypes; }
    }
}
