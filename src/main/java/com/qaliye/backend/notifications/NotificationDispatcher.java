package com.qaliye.backend.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private record DeviceInfo(String token, String locale, UUID userId) {}
    private record LocalizedNotification(String title, String body) {}

    private static final String DEVICES_FOR_USERS_SQL = """
            SELECT nd.device_token, au.preferred_language, nd.user_id
            FROM notification_devices nd
            JOIN app_users au ON au.id = nd.user_id
            WHERE nd.user_id IN (:userIds) AND nd.is_active = TRUE
            """;

    private static final String DEVICES_FOR_USER_SQL = """
            SELECT nd.device_token, au.preferred_language
            FROM notification_devices nd
            JOIN app_users au ON au.id = nd.user_id
            WHERE nd.user_id = :userId AND nd.is_active = TRUE
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ExpoPushClient expoPushClient;

    public NotificationDispatcher(NamedParameterJdbcTemplate jdbc, ExpoPushClient expoPushClient) {
        this.jdbc = jdbc;
        this.expoPushClient = expoPushClient;
    }

    @Async
    public void dispatchMatchNotification(UUID userOneId, UUID userTwoId, UUID matchId) {
        try {
            List<DeviceInfo> devices = jdbc.query(DEVICES_FOR_USERS_SQL,
                    Map.of("userIds", List.of(userOneId, userTwoId)),
                    (rs, rowNum) -> new DeviceInfo(
                            rs.getString("device_token"),
                            rs.getString("preferred_language"),
                            rs.getObject("user_id", UUID.class)
                    ));

            Map<String, Object> data = Map.of("type", "MATCH", "match_id", matchId.toString());
            List<ExpoPushClient.PushMessage> messages = new ArrayList<>();
            for (DeviceInfo d : devices) {
                LocalizedNotification ln = localizeMatch(d.locale());
                messages.add(new ExpoPushClient.PushMessage(d.token(), ln.title(), ln.body(), data));
            }
            expoPushClient.sendBatch(messages);
        } catch (Exception e) {
            log.error("dispatchMatchNotification failed for match {}: {}", matchId, e.getMessage());
        }
    }

    @Async
    public void dispatchMessageNotification(UUID recipientId, UUID matchId, String senderDisplayName) {
        try {
            List<DeviceInfo> devices = jdbc.query(DEVICES_FOR_USER_SQL,
                    Map.of("userId", recipientId),
                    (rs, rowNum) -> new DeviceInfo(
                            rs.getString("device_token"),
                            rs.getString("preferred_language"),
                            recipientId
                    ));

            Map<String, Object> data = Map.of("type", "NEW_MESSAGE", "match_id", matchId.toString());
            String name = senderDisplayName != null ? senderDisplayName : "Someone";
            List<ExpoPushClient.PushMessage> messages = new ArrayList<>();
            for (DeviceInfo d : devices) {
                LocalizedNotification ln = localizeMessage(d.locale(), name);
                messages.add(new ExpoPushClient.PushMessage(d.token(), ln.title(), ln.body(), data));
            }
            expoPushClient.sendBatch(messages);
        } catch (Exception e) {
            log.error("dispatchMessageNotification failed for match {}: {}", matchId, e.getMessage());
        }
    }

    @Async
    public void dispatchVerificationApprovedNotification(UUID userId) {
        try {
            List<DeviceInfo> devices = queryUserDevices(userId);
            Map<String, Object> data = Map.of("type", "VERIFICATION_APPROVED");
            List<ExpoPushClient.PushMessage> messages = new ArrayList<>();
            for (DeviceInfo d : devices) {
                LocalizedNotification ln = localizeVerificationApproved(d.locale());
                messages.add(new ExpoPushClient.PushMessage(d.token(), ln.title(), ln.body(), data));
            }
            expoPushClient.sendBatch(messages);
        } catch (Exception e) {
            log.error("dispatchVerificationApprovedNotification failed for user {}: {}", userId, e.getMessage());
        }
    }

    @Async
    public void dispatchVerificationRejectedNotification(UUID userId, String rejectionReason) {
        try {
            List<DeviceInfo> devices = queryUserDevices(userId);
            Map<String, Object> data = Map.of("type", "VERIFICATION_REJECTED");
            List<ExpoPushClient.PushMessage> messages = new ArrayList<>();
            for (DeviceInfo d : devices) {
                messages.add(new ExpoPushClient.PushMessage(
                        d.token(), "Verification unsuccessful", rejectionReason, data));
            }
            expoPushClient.sendBatch(messages);
        } catch (Exception e) {
            log.error("dispatchVerificationRejectedNotification failed for user {}: {}", userId, e.getMessage());
        }
    }

    private List<DeviceInfo> queryUserDevices(UUID userId) {
        return jdbc.query(DEVICES_FOR_USER_SQL,
                Map.of("userId", userId),
                (rs, rowNum) -> new DeviceInfo(
                        rs.getString("device_token"),
                        rs.getString("preferred_language"),
                        userId
                ));
    }

    private LocalizedNotification localizeMatch(String locale) {
        return switch (locale == null ? "en" : locale) {
            case "am" -> new LocalizedNotification("ተዛምዷል! 🎉", "አዲስ ጓደኛ አግኝተዋል!");
            case "ti" -> new LocalizedNotification("ተዛሚዱ! 🎉", "ሓዲሽ ተዛሚድካ/ኪ!");
            case "om" -> new LocalizedNotification("Walitti bu'an! 🎉", "Hiriyaa haaraa argatte!");
            default  -> new LocalizedNotification("It's a Match! 🎉", "You've got a new match!");
        };
    }

    private LocalizedNotification localizeMessage(String locale, String name) {
        return switch (locale == null ? "en" : locale) {
            case "am" -> new LocalizedNotification("አዲስ መልዕክት", "ከ" + name + " አዲስ መልዕክት");
            case "ti" -> new LocalizedNotification("ሓዱሽ መልኽቲ", "ሓዱሽ መልኽቲ ካብ " + name);
            case "om" -> new LocalizedNotification("Ergaa Haaraa", name + " irraa ergaa haaraa");
            default  -> new LocalizedNotification("New message", "New message from " + name);
        };
    }

    private LocalizedNotification localizeVerificationApproved(String locale) {
        return switch (locale == null ? "en" : locale) {
            case "am" -> new LocalizedNotification("ተረጋግጧል! ✓", "መገለጫዎ አሁን ተረጋግጧል።");
            case "ti" -> new LocalizedNotification("ተረጋጊጹ! ✓", "ፕሮፋይልካ/ኪ ሕጂ ተረጋጊጹ።");
            case "om" -> new LocalizedNotification("Mirkanaaye! ✓", "Profaayiliin kee amma mirkanaa'eera.");
            default  -> new LocalizedNotification("Verified! ✓", "Your profile is now verified.");
        };
    }
}
