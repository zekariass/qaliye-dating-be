package com.qaliye.backend.discovery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "storage")
public class StorageProperties {

    private SignedUrl signedUrl = new SignedUrl();

    public SignedUrl getSignedUrl() { return signedUrl; }
    public void setSignedUrl(SignedUrl signedUrl) { this.signedUrl = signedUrl; }

    public static class SignedUrl {
        private int ttlSeconds = 3600;
        public int ttlSeconds() { return ttlSeconds; }
        public void setTtlSeconds(int v) { this.ttlSeconds = v; }
    }
}
