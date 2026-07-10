package com.qaliye.backend.auth.sms;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sms")
public class SmsProperties {

    private String provider = "";
    private AfroMessage afromessage = new AfroMessage();
    private SmsEthiopia smsEthiopia = new SmsEthiopia();

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public AfroMessage getAfromessage() { return afromessage; }
    public void setAfromessage(AfroMessage afromessage) { this.afromessage = afromessage; }

    public SmsEthiopia getSmsEthiopia() { return smsEthiopia; }
    public void setSmsEthiopia(SmsEthiopia smsEthiopia) { this.smsEthiopia = smsEthiopia; }

    public static class AfroMessage {
        private String baseUrl = "https://api.afromessage.com/api/send";
        private String token = "";
        private String senderId = "";
        private String identifierId = "";

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }

        public String getSenderId() { return senderId; }
        public void setSenderId(String senderId) { this.senderId = senderId; }

        public String getIdentifierId() { return identifierId; }
        public void setIdentifierId(String identifierId) { this.identifierId = identifierId; }
    }

    public static class SmsEthiopia {
        private String baseUrl = "https://smsethiopia.et/api/sms/send";
        private String apiKey = "";

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    }
}
