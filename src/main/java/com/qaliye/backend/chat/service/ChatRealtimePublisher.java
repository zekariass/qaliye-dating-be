package com.qaliye.backend.chat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class ChatRealtimePublisher {

    private static final Logger log = LoggerFactory.getLogger(ChatRealtimePublisher.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String serviceRoleKey;
    private final String broadcastUrl;

    public ChatRealtimePublisher(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${supabase.service-role-key}") String serviceRoleKey,
            @Value("${supabase.realtime-broadcast-url}") String broadcastUrl) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.serviceRoleKey = serviceRoleKey;
        this.broadcastUrl = broadcastUrl;
    }

    public void publishBroadcast(String topic, String event, String payloadJson) {
        try {
            Map<String, Object> payload = objectMapper.readValue(payloadJson, Map.class);
            Map<String, Object> message = Map.of(
                    "topic", topic,
                    "event", event,
                    "payload", payload,
                    "private", true
            );
            Map<String, Object> body = Map.of("messages", List.of(message));
            String bodyJson = objectMapper.writeValueAsString(body);

            restClient.post()
                    .uri(broadcastUrl)
                    .header("apikey", serviceRoleKey)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceRoleKey)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(bodyJson)
                    .retrieve()
                    .toBodilessEntity();

            log.debug("Published broadcast to topic={} event={}", topic, event);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize broadcast payload", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish to Supabase Realtime: " + e.getMessage(), e);
        }
    }
}
