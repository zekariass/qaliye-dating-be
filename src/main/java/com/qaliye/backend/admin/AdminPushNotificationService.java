package com.qaliye.backend.admin;

import com.qaliye.backend.notifications.repository.NotificationOutboxRepository;
import com.qaliye.backend.user.UserStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AdminPushNotificationService {

    private static final Logger log = LoggerFactory.getLogger(AdminPushNotificationService.class);

    private static final String CHECK_ADMIN_SQL = """
            SELECT role FROM app_users WHERE id = :userId
            """;

    private static final String CHECK_TARGET_USER_SQL = """
            SELECT 1 FROM app_users WHERE id = :userId AND status IN ('ACTIVE', 'SUSPENDED', 'DEACTIVATED')
            """;

    private static final String AUDIT_LOG_SQL = """
            INSERT INTO audit_log (actor_user_id, action, target_table, target_id, details)
            VALUES (:actorId, :action, :targetTable, :targetId, :details::jsonb)
            """;

    private final NotificationOutboxRepository outboxRepo;
    private final NamedParameterJdbcTemplate jdbc;
    private final UserStatusService userStatusService;

    public AdminPushNotificationService(NotificationOutboxRepository outboxRepo,
                                        NamedParameterJdbcTemplate jdbc,
                                        UserStatusService userStatusService) {
        this.outboxRepo = outboxRepo;
        this.jdbc = jdbc;
        this.userStatusService = userStatusService;
    }

    @Transactional
    public Map<String, Object> sendPushNotification(UUID adminId, UUID targetUserId,
                                                     String title, String body) {
        requireAdmin(adminId);

        if (targetUserId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "target_user_id_required");
        }

        // Verify target user exists and is not deleted/banned
        List<Map<String, Object>> targetRows = jdbc.queryForList(CHECK_TARGET_USER_SQL,
                Map.of("userId", targetUserId));
        if (targetRows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user_not_found_or_deleted");
        }

        UUID eventId = UUID.randomUUID();
        String dedupeKey = "admin-push-" + eventId;
        String payloadJson = "{\"title\": \"" + escapeJson(title) + "\", "
                + "\"body\": \"" + escapeJson(body) + "\"}";

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime expiresAt = now.plusHours(24);

        outboxRepo.insert(
                eventId,
                "ACCOUNT_ALERT",
                targetUserId,
                adminId,
                null, null, null, null,
                dedupeKey,
                "admin-alert-" + targetUserId,
                payloadJson,
                expiresAt,
                now
        );

        String details = "{\"targetUserId\": \"" + targetUserId + "\""
                + ", \"title\": \"" + escapeJson(title) + "\""
                + ", \"body\": \"" + escapeJson(body) + "\""
                + "}";
        writeAuditLog(adminId, "ADMIN_PUSH_NOTIFICATION_SENT", "app_users", targetUserId, details);

        log.info("Admin {} sent push notification to user {}", adminId, targetUserId);

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("eventId", eventId);
        result.put("recipientUserId", targetUserId);
        result.put("notificationType", "ACCOUNT_ALERT");
        result.put("status", "QUEUED");
        return result;
    }

    private void requireAdmin(UUID userId) {
        UserStatusService.UserStatus status = userStatusService.getStatus(userId);
        if (status == null || !"ADMIN".equals(status.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin_access_required");
        }
    }

    private void writeAuditLog(UUID actorId, String action, String targetTable,
                               UUID targetId, String details) {
        jdbc.update(AUDIT_LOG_SQL, new MapSqlParameterSource()
                .addValue("actorId", actorId)
                .addValue("action", action)
                .addValue("targetTable", targetTable)
                .addValue("targetId", targetId)
                .addValue("details", details));
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
