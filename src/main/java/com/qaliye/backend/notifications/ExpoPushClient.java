package com.qaliye.backend.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class ExpoPushClient {

    private static final Logger log = LoggerFactory.getLogger(ExpoPushClient.class);

    public record PushMessage(String to, String title, String body, Map<String, Object> data) {}
    public record PushError(String error, String message) {}
    public record PushResponse(String status, String id, PushError details) {}

    private record ExpoResponse(List<PushResponse> data) {}

    private final RestClient restClient;
    private final NamedParameterJdbcTemplate jdbc;

    public ExpoPushClient(@Qualifier("expoRestClient") RestClient restClient,
                          NamedParameterJdbcTemplate jdbc) {
        this.restClient = restClient;
        this.jdbc = jdbc;
    }

    public void sendBatch(List<PushMessage> messages) {
        if (messages == null || messages.isEmpty()) return;
        try {
            ExpoResponse response = restClient.post()
                    .uri("/send")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(messages)
                    .retrieve()
                    .body(ExpoResponse.class);

            if (response != null && response.data() != null) {
                List<PushResponse> results = response.data();
                for (int i = 0; i < results.size() && i < messages.size(); i++) {
                    PushResponse result = results.get(i);
                    if (result.details() != null
                            && "DeviceNotRegistered".equals(result.details().error())) {
                        deactivateToken(messages.get(i).to());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Expo push batch failed: {}", e.getMessage());
        }
    }

    private void deactivateToken(String token) {
        try {
            jdbc.update(
                    "UPDATE notification_devices SET is_active = FALSE WHERE device_token = :token",
                    Map.of("token", token)
            );
        } catch (Exception e) {
            log.error("Failed to deactivate device token {}: {}", token, e.getMessage());
        }
    }
}
