package com.qaliye.backend.moderation;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class MessageModerationJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(MessageModerationJob.class);

    private static final Pattern PHONE_PATTERN =
            Pattern.compile("\\b(\\+?[\\d\\s\\-().]{7,20})\\b");
    private static final Pattern HANDLE_PATTERN =
            Pattern.compile("\\b@[A-Za-z0-9_.]{3,}\\b|\\b(whatsapp|telegram|instagram|snapchat|tiktok)\\b",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern MONEY_PATTERN =
            Pattern.compile("\\b(send money|wire|western union|moneygram|gift card)\\b",
                    Pattern.CASE_INSENSITIVE);

    private static final String SCAN_SQL = """
            SELECT id, sender_user_id, match_id, body FROM messages
            WHERE moderation_status = 'APPROVED'
              AND body IS NOT NULL
              AND created_at > NOW() - INTERVAL '1 hour'
            LIMIT 200
            """;

    private static final String FLAG_MESSAGE_SQL = """
            UPDATE messages SET moderation_status = 'REJECTED_FLAGGED' WHERE id = :messageId
            """;

    private static final String INSERT_AUTO_REPORT_SQL = """
            INSERT INTO user_reports (reporter_user_id, reported_user_id, report_type, related_message_id, description)
            VALUES (NULL, :senderUserId, 'AUTO_FLAGGED', :messageId,
                    'Automatically flagged by message moderation job')
            """;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        log.debug("MessageModerationJob executing");
        try {
            List<Map<String, Object>> messages = jdbc.queryForList(SCAN_SQL, Map.of());

            int flaggedCount = 0;
            for (Map<String, Object> row : messages) {
                String body = (String) row.get("body");
                if (isFlagged(body)) {
                    UUID messageId = (UUID) row.get("id");
                    UUID senderUserId = (UUID) row.get("sender_user_id");
                    try {
                        MapSqlParameterSource params = new MapSqlParameterSource()
                                .addValue("messageId", messageId)
                                .addValue("senderUserId", senderUserId);
                        jdbc.update(FLAG_MESSAGE_SQL, params);
                        jdbc.update(INSERT_AUTO_REPORT_SQL, params);
                        flaggedCount++;
                    } catch (Exception e) {
                        log.error("Failed to flag message {}: {}", messageId, e.getMessage());
                    }
                }
            }
            if (flaggedCount > 0) {
                log.info("MessageModerationJob flagged {} message(s)", flaggedCount);
            }
        } catch (Exception e) {
            log.error("MessageModerationJob failed: {}", e.getMessage());
            throw new JobExecutionException(e, false);
        }
    }

    private boolean isFlagged(String body) {
        if (body == null) return false;
        return PHONE_PATTERN.matcher(body).find()
                || HANDLE_PATTERN.matcher(body).find()
                || MONEY_PATTERN.matcher(body).find();
    }
}
