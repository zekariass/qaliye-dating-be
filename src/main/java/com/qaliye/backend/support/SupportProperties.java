package com.qaliye.backend.support;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "support")
public class SupportProperties {

    private String attachmentsBucket = "support-attachments";
    private int attachmentSignedUrlTtlSeconds = 300;
    private long maxFileSizeBytes = 52428800L;
    private int maxFilesPerMessage = 10;
    private List<String> allowedContentTypes = List.of(
            "image/jpeg",
            "image/png",
            "image/webp",
            "application/pdf",
            "text/plain"
    );
    private int voiceMaxDurationSeconds = 300;
    private long voiceMaxFileSizeBytes = 52428800L;
    private List<String> voiceAllowedContentTypes = List.of(
            "audio/m4a",
            "audio/mp4",
            "audio/aac",
            "audio/mpeg",
            "audio/wav",
            "audio/webm",
            "audio/x-m4a"
    );

    public String getAttachmentsBucket() {
        return attachmentsBucket;
    }

    public void setAttachmentsBucket(String attachmentsBucket) {
        this.attachmentsBucket = attachmentsBucket;
    }

    public int getAttachmentSignedUrlTtlSeconds() {
        return attachmentSignedUrlTtlSeconds;
    }

    public void setAttachmentSignedUrlTtlSeconds(int attachmentSignedUrlTtlSeconds) {
        this.attachmentSignedUrlTtlSeconds = attachmentSignedUrlTtlSeconds;
    }

    public long getMaxFileSizeBytes() {
        return maxFileSizeBytes;
    }

    public void setMaxFileSizeBytes(long maxFileSizeBytes) {
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public int getMaxFilesPerMessage() {
        return maxFilesPerMessage;
    }

    public void setMaxFilesPerMessage(int maxFilesPerMessage) {
        this.maxFilesPerMessage = maxFilesPerMessage;
    }

    public List<String> getAllowedContentTypes() {
        return allowedContentTypes;
    }

    public void setAllowedContentTypes(List<String> allowedContentTypes) {
        this.allowedContentTypes = allowedContentTypes;
    }

    public int getVoiceMaxDurationSeconds() {
        return voiceMaxDurationSeconds;
    }

    public void setVoiceMaxDurationSeconds(int voiceMaxDurationSeconds) {
        this.voiceMaxDurationSeconds = voiceMaxDurationSeconds;
    }

    public long getVoiceMaxFileSizeBytes() {
        return voiceMaxFileSizeBytes;
    }

    public void setVoiceMaxFileSizeBytes(long voiceMaxFileSizeBytes) {
        this.voiceMaxFileSizeBytes = voiceMaxFileSizeBytes;
    }

    public List<String> getVoiceAllowedContentTypes() {
        return voiceAllowedContentTypes;
    }

    public void setVoiceAllowedContentTypes(List<String> voiceAllowedContentTypes) {
        this.voiceAllowedContentTypes = voiceAllowedContentTypes;
    }
}
