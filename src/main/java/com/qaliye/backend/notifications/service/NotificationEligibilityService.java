package com.qaliye.backend.notifications.service;

import com.qaliye.backend.notifications.repository.NotificationOutboxRepository.OutboxRow;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class NotificationEligibilityService {

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationEligibilityService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record EligibilityResult(boolean eligible, String skipReason) {
        public static EligibilityResult ok() { return new EligibilityResult(true, null); }
        public static EligibilityResult skip(String reason) { return new EligibilityResult(false, reason); }
    }

    public EligibilityResult checkOutboxEligibility(OutboxRow row) {
        if (isExpired(row)) {
            return EligibilityResult.skip("NOTIFICATION_EXPIRED");
        }

        if (!isRecipientActive(row.recipientUserId())) {
            return EligibilityResult.skip("RECIPIENT_INELIGIBLE");
        }

        return switch (row.notificationType()) {
            case "CHAT_MESSAGE" -> checkChatMessageEligibility(row);
            case "MATCH_CREATED" -> checkMatchCreatedEligibility(row);
            case "LIKE_RECEIVED" -> checkLikeReceivedEligibility(row);
            case "ACCOUNT_ALERT" -> EligibilityResult.ok();
            case "MARKETING"     -> checkMarketingEligibility(row);
            default              -> EligibilityResult.skip("UNKNOWN_TYPE");
        };
    }

    public EligibilityResult checkDeliveryEligibility(UUID deviceUserId,
                                                       UUID recipientUserId,
                                                       boolean deviceActive,
                                                       String deviceEnv,
                                                       String configuredEnv,
                                                       OutboxRow outboxRow) {
        if (!deviceUserId.equals(recipientUserId)) {
            return EligibilityResult.skip("DEVICE_REASSIGNED");
        }
        if (!deviceActive) {
            return EligibilityResult.skip("DEVICE_INACTIVE");
        }
        if (!configuredEnv.equals(deviceEnv)) {
            return EligibilityResult.skip("ENVIRONMENT_MISMATCH");
        }
        if (isExpired(outboxRow)) {
            return EligibilityResult.skip("NOTIFICATION_EXPIRED");
        }
        return checkOutboxEligibility(outboxRow);
    }

    private boolean isExpired(OutboxRow row) {
        return row.expiresAt() != null && row.expiresAt().isBefore(OffsetDateTime.now());
    }

    private boolean isRecipientActive(UUID userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM app_users WHERE id = :userId AND status = 'ACTIVE'",
                Map.of("userId", userId), Integer.class);
        return count != null && count > 0;
    }

    private EligibilityResult checkChatMessageEligibility(OutboxRow row) {
        if (row.matchId() == null) return EligibilityResult.skip("NO_MATCH_ID");

        Integer count = jdbc.queryForObject("""
                SELECT COUNT(1)
                FROM matches m
                JOIN user_notification_preferences unp
                    ON unp.user_id = :recipientUserId
                WHERE m.id = :matchId
                  AND m.status = 'ACTIVE'
                  AND unp.push_enabled = TRUE
                  AND unp.message_notifications_enabled = TRUE
                  AND NOT EXISTS (
                      SELECT 1 FROM user_blocks ub
                      WHERE ub.status = 'ACTIVE'
                        AND (
                            (ub.blocker_user_id = m.user_one_id AND ub.blocked_user_id = m.user_two_id)
                            OR
                            (ub.blocker_user_id = m.user_two_id AND ub.blocked_user_id = m.user_one_id)
                        )
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM match_notification_settings mns
                      WHERE mns.match_id = :matchId
                        AND mns.user_id  = :recipientUserId
                        AND mns.muted_until > NOW()
                  )
                """,
                new MapSqlParameterSource()
                        .addValue("matchId", row.matchId())
                        .addValue("recipientUserId", row.recipientUserId()),
                Integer.class);

        return count != null && count > 0
                ? EligibilityResult.ok()
                : EligibilityResult.skip("CHAT_NOT_ELIGIBLE");
    }

    private EligibilityResult checkMatchCreatedEligibility(OutboxRow row) {
        if (row.matchId() == null) return EligibilityResult.skip("NO_MATCH_ID");

        Integer count = jdbc.queryForObject("""
                SELECT COUNT(1)
                FROM matches m
                JOIN user_notification_preferences unp
                    ON unp.user_id = :recipientUserId
                WHERE m.id = :matchId
                  AND unp.push_enabled = TRUE
                  AND unp.match_notifications_enabled = TRUE
                """,
                new MapSqlParameterSource()
                        .addValue("matchId", row.matchId())
                        .addValue("recipientUserId", row.recipientUserId()),
                Integer.class);

        return count != null && count > 0
                ? EligibilityResult.ok()
                : EligibilityResult.skip("MATCH_NOT_ELIGIBLE");
    }

    private EligibilityResult checkLikeReceivedEligibility(OutboxRow row) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(1)
                FROM user_notification_preferences unp
                WHERE unp.user_id = :recipientUserId
                  AND unp.push_enabled = TRUE
                  AND unp.like_notifications_enabled = TRUE
                """,
                Map.of("recipientUserId", row.recipientUserId()),
                Integer.class);

        return count != null && count > 0
                ? EligibilityResult.ok()
                : EligibilityResult.skip("LIKE_PREF_DISABLED");
    }

    private EligibilityResult checkMarketingEligibility(OutboxRow row) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(1)
                FROM user_notification_preferences unp
                WHERE unp.user_id = :recipientUserId
                  AND unp.push_enabled = TRUE
                  AND unp.marketing_notifications_enabled = TRUE
                  AND unp.marketing_notifications_opted_in_at IS NOT NULL
                  AND NULLIF(BTRIM(unp.marketing_notifications_consent_version), '') IS NOT NULL
                """,
                Map.of("recipientUserId", row.recipientUserId()),
                Integer.class);

        return count != null && count > 0
                ? EligibilityResult.ok()
                : EligibilityResult.skip("MARKETING_NOT_ELIGIBLE");
    }
}
