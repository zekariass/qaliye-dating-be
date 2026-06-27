package com.qaliye.backend.activity;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties(prefix = "activity-status")
@Validated
public class ActivityStatusProperties {

    @Positive
    private int onlineWindowSeconds = 180;

    @Positive
    private int recentlyActiveWindowSeconds = 900;

    @Positive
    private int heartbeatWriteMinIntervalSeconds = 60;

    @PostConstruct
    void validate() {
        if (recentlyActiveWindowSeconds <= onlineWindowSeconds) {
            throw new IllegalStateException(
                    "activity-status.recently-active-window-seconds (" + recentlyActiveWindowSeconds
                    + "s) must be greater than activity-status.online-window-seconds (" + onlineWindowSeconds + "s)");
        }
        if (heartbeatWriteMinIntervalSeconds >= onlineWindowSeconds) {
            throw new IllegalStateException(
                    "activity-status.heartbeat-write-min-interval-seconds (" + heartbeatWriteMinIntervalSeconds
                    + "s) must be lower than activity-status.online-window-seconds (" + onlineWindowSeconds + "s)");
        }
    }

    public int getOnlineWindowSeconds() { return onlineWindowSeconds; }
    public void setOnlineWindowSeconds(int onlineWindowSeconds) { this.onlineWindowSeconds = onlineWindowSeconds; }

    public int getRecentlyActiveWindowSeconds() { return recentlyActiveWindowSeconds; }
    public void setRecentlyActiveWindowSeconds(int recentlyActiveWindowSeconds) { this.recentlyActiveWindowSeconds = recentlyActiveWindowSeconds; }

    public int getHeartbeatWriteMinIntervalSeconds() { return heartbeatWriteMinIntervalSeconds; }
    public void setHeartbeatWriteMinIntervalSeconds(int heartbeatWriteMinIntervalSeconds) { this.heartbeatWriteMinIntervalSeconds = heartbeatWriteMinIntervalSeconds; }
}
