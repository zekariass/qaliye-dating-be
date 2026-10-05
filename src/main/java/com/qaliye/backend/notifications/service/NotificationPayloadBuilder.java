package com.qaliye.backend.notifications.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaliye.backend.notifications.repository.NotificationOutboxRepository.OutboxRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class NotificationPayloadBuilder {

    private static final Logger log = LoggerFactory.getLogger(NotificationPayloadBuilder.class);
    private static final String GENERIC_CHAT_TITLE = "Qaliye";
    private static final String GENERIC_CHAT_BODY  = "You have a new message";

    @SuppressWarnings("unused")
    private static final Set<String> PREVIEW_ELIGIBLE_TYPES =
            Set.of("TEXT", "ICEBREAKER", "PROMPT_REPLY");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationPayloadBuilder(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ExpoMessage(
            String to,
            String title,
            String body,
            Map<String, Object> data,
            String collapseId,
            String tag,
            Integer ttl,
            String priority,
            String channelId,
            String sound
    ) {
        public ExpoMessage(String to, String title, String body, Map<String, Object> data,
                           String collapseId, String tag, Integer ttl, String priority, String channelId) {
            this(to, title, body, data, collapseId, tag, ttl, priority, channelId, "notification.mp3");
        }
    }

    public ExpoMessage buildForDelivery(OutboxRow outbox, String deviceToken) {
        return switch (outbox.notificationType()) {
            case "CHAT_MESSAGE"  -> buildChatMessage(outbox, deviceToken);
            case "MATCH_CREATED" -> buildMatchCreated(outbox, deviceToken);
            case "LIKE_RECEIVED" -> buildLikeReceived(outbox, deviceToken);
            case "SUPERLIKE_RECEIVED" -> buildSuperLikeReceived(outbox, deviceToken);
            case "SUPER_MESSAGE_RECEIVED" -> buildSuperMessageReceived(outbox, deviceToken);
            case "ACCOUNT_ALERT" -> buildAccountAlert(outbox, deviceToken);
            case "MARKETING"     -> buildMarketing(outbox, deviceToken);
            default              -> throw new IllegalArgumentException(
                    "Unknown notification type: " + outbox.notificationType());
        };
    }

    private ExpoMessage buildChatMessage(OutboxRow outbox, String deviceToken) {
        String title = GENERIC_CHAT_TITLE;
        String body  = GENERIC_CHAT_BODY;

        boolean previewEnabled = isMessagePreviewEnabled(outbox.recipientUserId());
        if (previewEnabled && outbox.messageId() != null) {
            String preview = loadSafeMessagePreview(outbox.messageId());
            if (preview != null) {
                body = preview;
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("notification_type", "CHAT_MESSAGE");
        data.put("match_id", outbox.matchId() != null ? outbox.matchId().toString() : null);
        data.put("message_id", outbox.messageId() != null ? outbox.messageId().toString() : null);

        Integer ttl = outbox.expiresAt() != null
                ? (int) Math.max(1, ChronoUnit.SECONDS.between(OffsetDateTime.now(), outbox.expiresAt()))
                : 900;

        String collapseId = outbox.collapseKey();
        String tag = collapseId;

        return new ExpoMessage(deviceToken, title, body, data,
                collapseId, tag, ttl, "high", "chat", "notification.mp3");
    }

    private ExpoMessage buildMatchCreated(OutboxRow outbox, String deviceToken) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("notification_type", "MATCH_CREATED");
        data.put("match_id", outbox.matchId() != null ? outbox.matchId().toString() : null);

        return new ExpoMessage(deviceToken, "Qaliye", "It's a Match! 🎉", data,
                null, null, null, "high", "matches", "notification.mp3");
    }

    private ExpoMessage buildLikeReceived(OutboxRow outbox, String deviceToken) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("notification_type", "LIKE_RECEIVED");
        data.put("discovery_action_id", outbox.discoveryActionId() != null
                ? outbox.discoveryActionId().toString() : null);

        return new ExpoMessage(deviceToken, "Qaliye", "Someone liked your profile!", data,
                null, null, null, "normal", "likes", "notification.mp3");
    }

    private ExpoMessage buildSuperLikeReceived(OutboxRow outbox, String deviceToken) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("notification_type", "SUPERLIKE_RECEIVED");
        data.put("discovery_action_id", outbox.discoveryActionId() != null
                ? outbox.discoveryActionId().toString() : null);

        return new ExpoMessage(deviceToken, "Qaliye", "Someone superliked your profile! ⭐", data,
                null, null, null, "high", "likes", "notification.mp3");
    }

    private ExpoMessage buildSuperMessageReceived(OutboxRow outbox, String deviceToken) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("notification_type", "SUPER_MESSAGE_RECEIVED");
        data.put("super_message_id", extractSuperMessageId(outbox.payloadJson()));

        return new ExpoMessage(deviceToken, "Qaliye", "You received a Super Message! ✨", data,
                null, null, null, "high", "messages", "notification.mp3");
    }

    private ExpoMessage buildAccountAlert(OutboxRow outbox, String deviceToken) {
        String alertCode = extractAlertCode(outbox.payloadJson());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("notification_type", "ACCOUNT_ALERT");
        if (alertCode != null) {
            data.put("alert_code", alertCode);
        }
        // Forward every other textual payload field (session_id, request_id,
        // introduction_id, match_id, video_call_request_id, ...) so clients
        // can deep-link without a round-trip.
        data.putAll(extractJsonFields(outbox.payloadJson(),
                Set.of("notification_type", "notification_id", "alert_code")));

        String title = "Qaliye";
        String body  = alertBody(alertCode);

        return new ExpoMessage(deviceToken, title, body, data,
                null, null, null, "high", "alerts", "notification.mp3");
    }

    private static String alertBody(String alertCode) {
        if (alertCode == null) return "Important account update";
        return switch (alertCode) {
            case "VERIFICATION_APPROVED" -> "Your identity has been verified! ✅";
            case "VERIFICATION_REJECTED" -> "Your identity verification could not be approved.";
            case "PHOTO_APPROVED"        -> "Your profile photo has been approved! ✅";
            case "PHOTO_REJECTED"        -> "Your profile photo was not approved. Please upload a new one.";
            case "PAYMENT_APPROVED"      -> "Your payment has been approved! ✅";
            case "PAYMENT_REJECTED"      -> "Your payment could not be approved. Please contact support.";
            case "BLIND_DATE_REVEAL"     -> "Your Blind Date reveal is ready! ✨";
            case "BLIND_DATE_ELIMINATED" -> "This Blind Date has moved on without you.";
            case "BLIND_DATE_MATCHED"    -> "It's a match! Your Blind Date said yes too. 💕";
            case "BLIND_DATE_NO_MATCH"   -> "Your Blind Date reveal has ended without a match.";
            case "MATCHMAKING_REQUEST_CREATED"         -> "Your matchmaking request is live — our matchmakers are on it. ✨";
            case "MATCHMAKING_REQUEST_CANCELLED"       -> "Your matchmaking request was cancelled.";
            case "MATCHMAKING_REQUEST_EXPIRED"         -> "Your matchmaking request has expired.";
            case "MATCHMAKING_INTRODUCTION_PROPOSED"   -> "Your matchmaker found someone for you! 💌";
            case "MATCHMAKING_INTRODUCTION_DECLINED"   -> "A matchmaking introduction was declined.";
            case "MATCHMAKING_INTRODUCTION_CANCELLED"  -> "A matchmaking introduction was cancelled.";
            case "MATCHMAKING_INTRODUCTION_EXPIRED"    -> "A matchmaking introduction has expired.";
            case "MATCHMAKING_MATCHED"                 -> "It's a match! You both said yes. 💕";
            case "VIDEO_CALL_REQUESTED"   -> "Incoming video call request 📹";
            case "VIDEO_CALL_ACCEPTED"    -> "Your video call request was accepted!";
            case "VIDEO_CALL_DECLINED"    -> "Your video call request was declined.";
            case "VIDEO_CALL_CANCELLED"   -> "The video call request was cancelled.";
            case "VIDEO_CALL_EXPIRED"     -> "Your video call request has expired.";
            case "AUDIO_CALL_REQUESTED"   -> "Incoming audio call request 📞";
            case "AUDIO_CALL_ACCEPTED"    -> "Your audio call request was accepted!";
            case "AUDIO_CALL_DECLINED"    -> "Your audio call request was declined.";
            case "AUDIO_CALL_CANCELLED"   -> "The audio call request was cancelled.";
            case "AUDIO_CALL_EXPIRED"     -> "Your audio call request has expired.";
            default                      -> "Important account update";
        };
    }

    private String extractAlertCode(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) return null;
        try {
            var node = MAPPER.readTree(payloadJson);
            if (node.has("alert_code")) return node.get("alert_code").asText();
        } catch (Exception ignored) {}
        return null;
    }

    private String extractJsonString(String payloadJson, String field) {
        if (payloadJson == null || payloadJson.isBlank()) return null;
        try {
            var node = MAPPER.readTree(payloadJson);
            if (node.has(field) && node.get(field).isTextual()) return node.get(field).asText();
        } catch (Exception ignored) {}
        return null;
    }

    /** Returns all textual fields of payload_json except the excluded keys. */
    private Map<String, Object> extractJsonFields(String payloadJson, Set<String> exclude) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (payloadJson == null || payloadJson.isBlank()) return fields;
        try {
            var node = MAPPER.readTree(payloadJson);
            node.fields().forEachRemaining(entry -> {
                if (!exclude.contains(entry.getKey()) && entry.getValue().isTextual()) {
                    fields.put(entry.getKey(), entry.getValue().asText());
                }
            });
        } catch (Exception ignored) {}
        return fields;
    }

    private ExpoMessage buildMarketing(OutboxRow outbox, String deviceToken) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("notification_type", "MARKETING");
        data.put("campaign_id", outbox.campaignId() != null
                ? outbox.campaignId().toString() : null);

        String title = "Qaliye";
        String body  = "Check out what's new";

        if (outbox.campaignId() != null) {
            Map<String, Object> campaign = loadCampaignContent(outbox.campaignId());
            if (campaign != null) {
                title = (String) campaign.getOrDefault("title", title);
                body  = (String) campaign.getOrDefault("body", body);
                Object navPayload = campaign.get("navigation_payload");
                Map<String, Object> navMap = parseNavigationPayload(navPayload);
                if (navMap != null && !navMap.isEmpty()) {
                    data.put("navigation", navMap);
                }
            }
        }

        return new ExpoMessage(deviceToken, title, body, data,
                outbox.collapseKey(), null, null, "normal", "marketing", "notification.mp3");
    }

    private boolean isMessagePreviewEnabled(UUID userId) {
        if (userId == null) return false;
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM user_notification_preferences "
                            + "WHERE user_id = :userId AND message_preview_enabled = TRUE",
                    Map.of("userId", userId), Integer.class);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String loadSafeMessagePreview(UUID messageId) {
        try {
            String body = jdbc.queryForObject(
                    "SELECT body FROM messages "
                            + "WHERE id = :messageId "
                            + "  AND deleted_at IS NULL "
                            + "  AND moderation_status = 'APPROVED' "
                            + "  AND message_type IN ('TEXT', 'ICEBREAKER', 'PROMPT_REPLY')",
                    Map.of("messageId", messageId), String.class);
            if (body == null) return null;
            String trimmed = body.trim();
            return trimmed.length() > 100 ? trimmed.substring(0, 97) + "..." : trimmed;
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> loadCampaignContent(UUID campaignId) {
        try {
            return jdbc.queryForMap(
                    "SELECT title, body, navigation_payload FROM notification_campaigns WHERE id = :campaignId",
                    Map.of("campaignId", campaignId));
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseNavigationPayload(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        String json = raw.toString();
        if (json.isBlank() || json.equals("{}")) return null;
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse navigation_payload: {}", e.getMessage());
            return null;
        }
    }

    private String extractSuperMessageId(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) return null;
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payloadJson);
            if (node.has("super_message_id")) return node.get("super_message_id").asText();
        } catch (Exception ignored) {}
        return null;
    }
}
