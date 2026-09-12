package com.qaliye.backend.links;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "links")
public class LinkProperties {

    private String terms = "";
    private String privacy = "";
    private String iosAppStore = "";
    private String playStore = "";

    public String getTerms() { return terms; }
    public void setTerms(String terms) { this.terms = terms; }

    public String getPrivacy() { return privacy; }
    public void setPrivacy(String privacy) { this.privacy = privacy; }

    public String getIosAppStore() { return iosAppStore; }
    public void setIosAppStore(String iosAppStore) { this.iosAppStore = iosAppStore; }

    public String getPlayStore() { return playStore; }
    public void setPlayStore(String playStore) { this.playStore = playStore; }
}
