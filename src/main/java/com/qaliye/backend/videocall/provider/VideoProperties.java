package com.qaliye.backend.videocall.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Provider-agnostic video-platform configuration.
 *
 * {@code video.platform} selects which {@link VideoCallProvider} implementation
 * backs accepted video-call requests (e.g. AGORA). Adding a vendor later =
 * a new provider bean plus a config block — no schema or business-logic change.
 */
@ConfigurationProperties(prefix = "video")
public class VideoProperties {

    /** Active VideoCallProvider name() — e.g. AGORA. */
    private String platform = "AGORA";

    private Agora agora = new Agora();

    private Call call = new Call();

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }

    public Agora getAgora() { return agora; }
    public void setAgora(Agora agora) { this.agora = agora; }

    public Call getCall() { return call; }
    public void setCall(Call call) { this.call = call; }

    /**
     * Per-call maximum durations, keyed by call_type. The deadline is
     * anchored on the first join (call_deadline_at) and enforced by minting
     * RTC tokens that expire no later than it — Agora drops both
     * participants server-side when the privilege timestamp passes.
     */
    public static class Call {
        /** Max seconds a VIDEO call may run. */
        private int videoMaxSeconds = 1800;
        /** Max seconds an AUDIO call may run. */
        private int audioMaxSeconds = 1800;

        public int getVideoMaxSeconds() { return videoMaxSeconds; }
        public void setVideoMaxSeconds(int videoMaxSeconds) { this.videoMaxSeconds = videoMaxSeconds; }

        public int getAudioMaxSeconds() { return audioMaxSeconds; }
        public void setAudioMaxSeconds(int audioMaxSeconds) { this.audioMaxSeconds = audioMaxSeconds; }
    }

    public static class Agora {
        private String appId;
        private String appCertificate;
        /** Join-token lifetime in seconds. */
        private int tokenTtlSeconds = 3600;

        public String getAppId() { return appId; }
        public void setAppId(String appId) { this.appId = appId; }

        public String getAppCertificate() { return appCertificate; }
        public void setAppCertificate(String appCertificate) { this.appCertificate = appCertificate; }

        public int getTokenTtlSeconds() { return tokenTtlSeconds; }
        public void setTokenTtlSeconds(int tokenTtlSeconds) { this.tokenTtlSeconds = tokenTtlSeconds; }
    }
}
