package com.qaliye.backend.notifications.service;

import com.qaliye.backend.notifications.repository.NotificationOutboxRepository.OutboxRow;
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

    private static final String GENERIC_CHAT_TITLE = "Qaliye";
    private static final String GENERIC_CHAT_BODY  = "You have a new message";

    @SuppressWarnings("unused")
    private static final Set<String> PREVIEW_ELIGIBLE_TYPES =
            Set.of("TEXT", "ICEBREAKER", "PROMPT_REPLY");

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
            String channelId
    ) {}

    public ExpoMessage buildForDelivery(OutboxRow outbox, String deviceToken) {
        return switch (outbox.notificationType()) {
            case "CHAT_MESSAGE"  -> buildChatMessage(outbox, deviceToken);
            case "MATCH_CREATED" -> buildMatchCreated(outbox, deviceToken);
            case "LIKE_RECEIVED" -> buildLikeReceived(outbox, deviceToken);
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
                collapseId, tag, ttl, "high", "chat");
    }

    private ExpoMessage buildMatchCreated(OutboxRow outbox, String deviceToken) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("notification_type", "MATCH_CREATED");
        data.put("match_id", outbox.matchId() != null ? outbox.matchId().toString() : null);

        return new ExpoMessage(deviceToken, "Qaliye", "It's a Match! 🎉", data,
                null, null, null, "high", "matches");
    }

    private ExpoMessage buildLikeReceived(OutboxRow outbox, String deviceToken) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("notification_type", "LIKE_RECEIVED");
        data.put("discovery_action_id", outbox.discoveryActionId() != null
                ? outbox.discoveryActionId().toString() : null);

        return new ExpoMessage(deviceToken, "Qaliye", "Someone liked your profile!", data,
                null, null, null, "normal", "likes");
    }

    private ExpoMessage buildAccountAlert(OutboxRow outbox, String deviceToken) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("notification_type", "ACCOUNT_ALERT");

        return new ExpoMessage(deviceToken, "Qaliye", "Important account update", data,
                null, null, null, "high", "alerts");
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
            }
        }

        return new ExpoMessage(deviceToken, title, body, data,
                outbox.collapseKey(), null, null, "normal", "marketing");
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
                    "SELECT title, body FROM notification_campaigns WHERE id = :campaignId",
                    Map.of("campaignId", campaignId));
        } catch (Exception e) {
            return null;
        }
    }
}
