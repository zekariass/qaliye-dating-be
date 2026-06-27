package com.qaliye.backend.notifications.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "push")
public class PushProperties {

    private boolean enabled = true;

    @NotBlank
    @Pattern(regexp = "DEVELOPMENT|PREVIEW|PRODUCTION")
    private String appEnvironment = "PRODUCTION";

    @Valid
    @NotNull
    private Expo expo = new Expo();

    @Valid
    @NotNull
    private Outbox outbox = new Outbox();

    @Valid
    @NotNull
    private Delivery delivery = new Delivery();

    @Valid
    @NotNull
    private Receipts receipts = new Receipts();

    @Valid
    @NotNull
    private Marketing marketing = new Marketing();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getAppEnvironment() { return appEnvironment; }
    public void setAppEnvironment(String appEnvironment) { this.appEnvironment = appEnvironment; }

    public Expo getExpo() { return expo; }
    public void setExpo(Expo expo) { this.expo = expo; }

    public Outbox getOutbox() { return outbox; }
    public void setOutbox(Outbox outbox) { this.outbox = outbox; }

    public Delivery getDelivery() { return delivery; }
    public void setDelivery(Delivery delivery) { this.delivery = delivery; }

    public Receipts getReceipts() { return receipts; }
    public void setReceipts(Receipts receipts) { this.receipts = receipts; }

    public Marketing getMarketing() { return marketing; }
    public void setMarketing(Marketing marketing) { this.marketing = marketing; }

    public static class Expo {
        @NotBlank
        private String sendUrl = "https://exp.host/--/api/v2/push/send";

        @NotBlank
        private String receiptsUrl = "https://exp.host/--/api/v2/push/getReceipts";

        private String accessToken = "";

        @Min(1) @Max(100)
        private int sendBatchSize = 100;

        @Min(1) @Max(1000)
        private int receiptBatchSize = 1000;

        public String getSendUrl() { return sendUrl; }
        public void setSendUrl(String sendUrl) { this.sendUrl = sendUrl; }

        public String getReceiptsUrl() { return receiptsUrl; }
        public void setReceiptsUrl(String receiptsUrl) { this.receiptsUrl = receiptsUrl; }

        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

        public int getSendBatchSize() { return sendBatchSize; }
        public void setSendBatchSize(int sendBatchSize) { this.sendBatchSize = sendBatchSize; }

        public int getReceiptBatchSize() { return receiptBatchSize; }
        public void setReceiptBatchSize(int receiptBatchSize) { this.receiptBatchSize = receiptBatchSize; }
    }

    public static class Outbox {
        @Positive
        private int batchSize = 100;

        @Positive
        private long pollIntervalMs = 1000;

        @Positive
        private int leaseSeconds = 60;

        @Positive
        private int maxAttempts = 10;

        @Positive
        private long maxBackoffSeconds = 900;

        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

        public long getPollIntervalMs() { return pollIntervalMs; }
        public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }

        public int getLeaseSeconds() { return leaseSeconds; }
        public void setLeaseSeconds(int leaseSeconds) { this.leaseSeconds = leaseSeconds; }

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

        public long getMaxBackoffSeconds() { return maxBackoffSeconds; }
        public void setMaxBackoffSeconds(long maxBackoffSeconds) { this.maxBackoffSeconds = maxBackoffSeconds; }
    }

    public static class Delivery {
        @Positive
        private long pollIntervalMs = 1000;

        @Positive
        private int leaseSeconds = 60;

        @Positive
        private int maxAttempts = 10;

        @Positive
        private long maxBackoffSeconds = 900;

        public long getPollIntervalMs() { return pollIntervalMs; }
        public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }

        public int getLeaseSeconds() { return leaseSeconds; }
        public void setLeaseSeconds(int leaseSeconds) { this.leaseSeconds = leaseSeconds; }

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

        public long getMaxBackoffSeconds() { return maxBackoffSeconds; }
        public void setMaxBackoffSeconds(long maxBackoffSeconds) { this.maxBackoffSeconds = maxBackoffSeconds; }
    }

    public static class Receipts {
        @Min(1)
        private int initialDelayMinutes = 15;

        @Min(1) @Max(23)
        private int deadlineHours = 23;

        public int getInitialDelayMinutes() { return initialDelayMinutes; }
        public void setInitialDelayMinutes(int initialDelayMinutes) { this.initialDelayMinutes = initialDelayMinutes; }

        public int getDeadlineHours() { return deadlineHours; }
        public void setDeadlineHours(int deadlineHours) { this.deadlineHours = deadlineHours; }
    }

    public static class Marketing {
        @Positive
        private int minIntervalDays = 7;

        @Positive
        private int reservationMinutes = 30;

        public int getMinIntervalDays() { return minIntervalDays; }
        public void setMinIntervalDays(int minIntervalDays) { this.minIntervalDays = minIntervalDays; }

        public int getReservationMinutes() { return reservationMinutes; }
        public void setReservationMinutes(int reservationMinutes) { this.reservationMinutes = reservationMinutes; }
    }
}
