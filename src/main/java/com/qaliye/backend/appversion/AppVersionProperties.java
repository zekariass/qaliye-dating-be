package com.qaliye.backend.appversion;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.version")
public class AppVersionProperties {

    private PlatformConfig android = new PlatformConfig();
    private PlatformConfig ios = new PlatformConfig();

    @PostConstruct
    void validate() {
        validatePlatformConfig("android", android);
        validatePlatformConfig("ios", ios);
    }

    private void validatePlatformConfig(String name, PlatformConfig config) {
        if (!isValidVersion(config.getLatest())) {
            throw new IllegalStateException(
                    "app.version." + name + ".latest is not a valid semantic version: '" + config.getLatest() + "'");
        }
        if (!isValidVersion(config.getMinimum())) {
            throw new IllegalStateException(
                    "app.version." + name + ".minimum is not a valid semantic version: '" + config.getMinimum() + "'");
        }
    }

    private boolean isValidVersion(String version) {
        return version != null && version.matches("^\\d+\\.\\d+\\.\\d+$");
    }

    public PlatformConfig getAndroid() { return android; }
    public void setAndroid(PlatformConfig android) { this.android = android; }

    public PlatformConfig getIos() { return ios; }
    public void setIos(PlatformConfig ios) { this.ios = ios; }

    public static class PlatformConfig {
        private String latest = "1.0.0";
        private String minimum = "1.0.0";
        private boolean forceUpdate = false;
        private String storeUrl = "";

        public String getLatest() { return latest; }
        public void setLatest(String latest) { this.latest = latest; }

        public String getMinimum() { return minimum; }
        public void setMinimum(String minimum) { this.minimum = minimum; }

        public boolean isForceUpdate() { return forceUpdate; }
        public void setForceUpdate(boolean forceUpdate) { this.forceUpdate = forceUpdate; }

        public String getStoreUrl() { return storeUrl; }
        public void setStoreUrl(String storeUrl) { this.storeUrl = storeUrl; }
    }
}
