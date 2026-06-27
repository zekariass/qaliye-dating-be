package com.qaliye.backend.notifications;

import com.qaliye.backend.notifications.config.PushProperties;
import com.qaliye.backend.notifications.service.NotificationPayloadBuilder.ExpoMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ExpoPushClient {

    private static final Logger log = LoggerFactory.getLogger(ExpoPushClient.class);

    public record TicketResult(String status, String ticketId, String errorCode, String errorMessage) {
        public boolean isOk()               { return "ok".equals(status); }
        public boolean isDeviceNotRegistered() {
            return "error".equals(status) && "DeviceNotRegistered".equals(errorCode);
        }
        public boolean isRetryable() {
            if (!"error".equals(status)) return false;
            return errorCode == null
                    || "MessageRateExceeded".equals(errorCode)
                    || errorCode.startsWith("5");
        }
    }

    public record ReceiptResult(String status, String errorCode, String errorMessage) {
        public boolean isOk()               { return "ok".equals(status); }
        public boolean isDeviceNotRegistered() {
            return "error".equals(status) && "DeviceNotRegistered".equals(errorCode);
        }
    }

    private record ExpoSendMessage(
            String to,
            String title,
            String body,
            Map<String, Object> data,
            String collapseId,
            String tag,
            Integer ttl,
            String priority,
            String channelId
    ) {}

    private final RestClient restClient;
    private final PushProperties pushProperties;

    public ExpoPushClient(RestClient.Builder restClientBuilder,
                          PushProperties pushProperties) {
        this.pushProperties = pushProperties;
        this.restClient = restClientBuilder.build();
    }

    public List<TicketResult> sendBatch(List<ExpoMessage> messages) {
        if (messages == null || messages.isEmpty()) return List.of();

        List<ExpoSendMessage> payload = messages.stream()
                .map(m -> new ExpoSendMessage(
                        m.to(), m.title(), m.body(), m.data(),
                        m.collapseId(), m.tag(), m.ttl(), m.priority(), m.channelId()))
                .toList();

        try {
            Map<?, ?> response = restClient.post()
                    .uri(pushProperties.getExpo().getSendUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> addAuthHeader(h))
                    .body(payload)
                    .retrieve()
                    .body(Map.class);

            if (response == null || !(response.get("data") instanceof List<?> dataList)) {
                log.warn("Unexpected Expo send response shape");
                return buildErrorResults(messages.size(), "UNKNOWN_RESPONSE");
            }

            List<TicketResult> results = new ArrayList<>(dataList.size());
            for (int i = 0; i < dataList.size(); i++) {
                Object entry = dataList.get(i);
                if (entry instanceof Map<?, ?> m) {
                    String status = (String) m.get("status");
                    if ("ok".equals(status)) {
                        results.add(new TicketResult("ok", (String) m.get("id"), null, null));
                    } else {
                        Map<?, ?> details = (Map<?, ?>) m.get("details");
                        String errCode = details != null ? (String) details.get("error") : null;
                        String errMsg  = details != null ? (String) details.get("message") : null;
                        results.add(new TicketResult("error", null, errCode, errMsg));
                    }
                } else {
                    results.add(new TicketResult("error", null, "PARSE_ERROR", null));
                }
            }
            return results;

        } catch (Exception e) {
            log.error("Expo sendBatch failed: {}", e.getMessage());
            throw new ExpoProviderException("Expo send failed: " + e.getMessage(), e);
        }
    }

    public Map<String, ReceiptResult> fetchReceipts(List<String> ticketIds) {
        if (ticketIds == null || ticketIds.isEmpty()) return Map.of();

        try {
            Map<?, ?> response = restClient.post()
                    .uri(pushProperties.getExpo().getReceiptsUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> addAuthHeader(h))
                    .body(Map.of("ids", ticketIds))
                    .retrieve()
                    .body(Map.class);

            if (response == null || !(response.get("data") instanceof Map<?, ?> dataMap)) {
                log.warn("Unexpected Expo receipts response shape");
                return Map.of();
            }

            Map<String, ReceiptResult> results = new HashMap<>();
            for (Map.Entry<?, ?> entry : dataMap.entrySet()) {
                String ticketId = (String) entry.getKey();
                if (entry.getValue() instanceof Map<?, ?> r) {
                    String status = (String) r.get("status");
                    if ("ok".equals(status)) {
                        results.put(ticketId, new ReceiptResult("ok", null, null));
                    } else {
                        Map<?, ?> details = (Map<?, ?>) r.get("details");
                        String errCode = details != null ? (String) details.get("error") : null;
                        String errMsg  = (String) r.get("message");
                        results.put(ticketId, new ReceiptResult("error", errCode, errMsg));
                    }
                }
            }
            return results;

        } catch (Exception e) {
            log.error("Expo fetchReceipts failed: {}", e.getMessage());
            throw new ExpoProviderException("Expo receipts failed: " + e.getMessage(), e);
        }
    }

    private void addAuthHeader(org.springframework.http.HttpHeaders headers) {
        String token = pushProperties.getExpo().getAccessToken();
        if (token != null && !token.isBlank()) {
            headers.setBearerAuth(token);
        }
    }

    private List<TicketResult> buildErrorResults(int count, String errorCode) {
        List<TicketResult> results = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            results.add(new TicketResult("error", null, errorCode, null));
        }
        return results;
    }

    public static class ExpoProviderException extends RuntimeException {
        public ExpoProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
