package com.qaliye.backend.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "billing")
public class BillingProperties {

    private int boostDurationMinutes = 30;
    private int paymentOrderExpiryHours = 48;
    private int receiptSignedUrlTtlSeconds = 300;
    private int manualTransferMaxAgeHours = 48;

    private Revenuecat revenuecat = new Revenuecat();
    private Chapa chapa = new Chapa();
    private ArifPay arifPay = new ArifPay();
    private Verifier verifier = new Verifier();
    private PaymentInstructions paymentInstructions = new PaymentInstructions();

    public int getBoostDurationMinutes() { return boostDurationMinutes; }
    public void setBoostDurationMinutes(int boostDurationMinutes) { this.boostDurationMinutes = boostDurationMinutes; }

    public int getPaymentOrderExpiryHours() { return paymentOrderExpiryHours; }
    public void setPaymentOrderExpiryHours(int paymentOrderExpiryHours) { this.paymentOrderExpiryHours = paymentOrderExpiryHours; }

    public int getReceiptSignedUrlTtlSeconds() { return receiptSignedUrlTtlSeconds; }
    public void setReceiptSignedUrlTtlSeconds(int receiptSignedUrlTtlSeconds) { this.receiptSignedUrlTtlSeconds = receiptSignedUrlTtlSeconds; }

    public int getManualTransferMaxAgeHours() { return manualTransferMaxAgeHours; }
    public void setManualTransferMaxAgeHours(int manualTransferMaxAgeHours) { this.manualTransferMaxAgeHours = manualTransferMaxAgeHours; }

    public Revenuecat getRevenuecat() { return revenuecat; }
    public void setRevenuecat(Revenuecat revenuecat) { this.revenuecat = revenuecat; }

    public Chapa getChapa() { return chapa; }
    public void setChapa(Chapa chapa) { this.chapa = chapa; }

    public ArifPay getArifPay() { return arifPay; }
    public void setArifPay(ArifPay arifPay) { this.arifPay = arifPay; }

    public Verifier getVerifier() { return verifier; }
    public void setVerifier(Verifier verifier) { this.verifier = verifier; }

    public PaymentInstructions getPaymentInstructions() { return paymentInstructions; }
    public void setPaymentInstructions(PaymentInstructions paymentInstructions) { this.paymentInstructions = paymentInstructions; }

    public static class Revenuecat {
        private String webhookAuthorizationToken = "";
        private String apiKey = "";
        private String apiBaseUrl = "https://api.revenuecat.com/v1";

        public String getWebhookAuthorizationToken() { return webhookAuthorizationToken; }
        public void setWebhookAuthorizationToken(String webhookAuthorizationToken) { this.webhookAuthorizationToken = webhookAuthorizationToken; }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        public String getApiBaseUrl() { return apiBaseUrl; }
        public void setApiBaseUrl(String apiBaseUrl) { this.apiBaseUrl = apiBaseUrl; }
    }

    public static class ArifPay {
        private String secretKey = "";
        private String webhookSecret = "";
        private String baseUrl = "https://gateway.arifpay.net";
        private String webhookUrl = "";

        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

        public String getWebhookSecret() { return webhookSecret; }
        public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getWebhookUrl() { return webhookUrl; }
        public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }
    }

    public static class Chapa {
        private String secretKey = "";
        private String webhookSecret = "";
        private String baseUrl = "https://api.chapa.co/v1";
        private String webhookUrl = "";
        private String returnUrl = "";

        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

        public String getWebhookSecret() { return webhookSecret; }
        public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getWebhookUrl() { return webhookUrl; }
        public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }

        public String getReturnUrl() { return returnUrl; }
        public void setReturnUrl(String returnUrl) { this.returnUrl = returnUrl; }
    }

    public static class Verifier {
        private String provider = "VERIFY_ET";
        private String apiKey = "";
        private String baseUrl = "https://verify.et";
        private String webhookSecret = "";
        private String webhookUrl = "";

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getWebhookSecret() { return webhookSecret; }
        public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }

        public String getWebhookUrl() { return webhookUrl; }
        public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }
    }

    public static class PaymentInstructions {
        private String bankName = "Commercial Bank of Ethiopia";
        private String accountNumber = "";
        private String accountName = "Qaliye Technologies";
        private String telebirrShortCode = "";

        public String getBankName() { return bankName; }
        public void setBankName(String bankName) { this.bankName = bankName; }

        public String getAccountNumber() { return accountNumber; }
        public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }

        public String getAccountName() { return accountName; }
        public void setAccountName(String accountName) { this.accountName = accountName; }

        public String getTelebirrShortCode() { return telebirrShortCode; }
        public void setTelebirrShortCode(String telebirrShortCode) { this.telebirrShortCode = telebirrShortCode; }
    }
}
