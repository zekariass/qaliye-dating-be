package com.qaliye.backend.messaging;

import com.qaliye.backend.chat.service.MatchLifecycleService;
import com.qaliye.backend.notifications.NotificationDispatcher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class MessagingService {

    public record MessageResult(MessageDto dto, boolean isNew) {}

    private record MatchRow(UUID id, UUID userOneId, UUID userTwoId, String status) {}

    private static final Pattern PHONE_PATTERN =
            Pattern.compile("\\b(\\+?[\\d\\s\\-().]{7,20})\\b");
    private static final Pattern HANDLE_PATTERN =
            Pattern.compile("\\b@[A-Za-z0-9_.]{3,}\\b|\\b(whatsapp|telegram|instagram|snapchat|tiktok)\\b",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern MONEY_PATTERN =
            Pattern.compile("\\b(send money|wire|western union|moneygram|gift card)\\b",
                    Pattern.CASE_INSENSITIVE);

    private static final String FETCH_MATCH_SQL = """
            SELECT id, user_one_id, user_two_id, status
            FROM matches
            WHERE id = :matchId
            """;

    private static final String BLOCK_CHECK_SQL = """
            SELECT 1 FROM user_blocks
            WHERE ((blocker_user_id = :callerId AND blocked_user_id = :recipientId)
                OR (blocker_user_id = :recipientId AND blocked_user_id = :callerId))
              AND status = 'ACTIVE'
            """;

    private static final String INSERT_MESSAGE_SQL = """
            INSERT INTO messages
                (match_id, sender_user_id, client_message_id, message_type, body,
                 storage_bucket, storage_path, moderation_status)
            VALUES
                (:matchId, :callerId, :clientMessageId, :messageType, :body,
                 :storageBucket, :storagePath, :moderationStatus)
            ON CONFLICT (sender_user_id, client_message_id) DO NOTHING
            RETURNING id, match_id, sender_user_id, message_type, body,
                      storage_bucket, storage_path, moderation_status, created_at
            """;

    private static final String FETCH_EXISTING_MESSAGE_SQL = """
            SELECT id, match_id, sender_user_id, message_type, body,
                   storage_bucket, storage_path, moderation_status, created_at
            FROM messages
            WHERE sender_user_id = :callerId AND client_message_id = :clientMessageId
            """;

    private static final String UNMATCH_SQL = """
            UPDATE matches SET
                status           = 'ENDED',
                end_reason       = 'USER_UNMATCH',
                ended_by_user_id = :callerId,
                ended_at         = NOW(),
                updated_at       = NOW()
            WHERE id = :matchId AND status = 'ACTIVE'
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final NotificationDispatcher notificationDispatcher;
    private final MatchLifecycleService matchLifecycleService;

    public MessagingService(NamedParameterJdbcTemplate jdbc,
                            NotificationDispatcher notificationDispatcher,
                            MatchLifecycleService matchLifecycleService) {
        this.jdbc = jdbc;
        this.notificationDispatcher = notificationDispatcher;
        this.matchLifecycleService = matchLifecycleService;
    }

    @Transactional
    public MessageResult sendMessage(UUID callerId, SendMessageRequest request) {
        MatchRow match = fetchAndValidateParticipant(request.getMatchId(), callerId);

        if (!"ACTIVE".equals(match.status())) {
            throw new MessagingException(403, "match_not_active");
        }

        UUID recipientId = callerId.equals(match.userOneId()) ? match.userTwoId() : match.userOneId();

        List<Integer> blocked = jdbc.query(BLOCK_CHECK_SQL,
                Map.of("callerId", callerId, "recipientId", recipientId),
                (rs, rowNum) -> rs.getInt(1));
        if (!blocked.isEmpty()) {
            throw new MessagingException(403, "blocked");
        }

        boolean isTextType = "TEXT".equals(request.getMessageType())
                || "ICEBREAKER".equals(request.getMessageType())
                || "PROMPT_REPLY".equals(request.getMessageType());
        boolean isMediaType = "IMAGE".equals(request.getMessageType())
                || "VOICE".equals(request.getMessageType());

        if (isTextType && (request.getBody() == null || request.getBody().isBlank())) {
            throw new MessagingException(422, "body_required_for_text_message");
        }
        if (isMediaType && (request.getStorageBucket() == null || request.getStoragePath() == null)) {
            throw new MessagingException(422, "storage_path_required_for_media_message");
        }

        String moderationStatus = determineModerationStatus(request.getBody());

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("matchId", request.getMatchId())
                .addValue("callerId", callerId)
                .addValue("clientMessageId", request.getClientMessageId())
                .addValue("messageType", request.getMessageType())
                .addValue("body", request.getBody())
                .addValue("storageBucket", request.getStorageBucket())
                .addValue("storagePath", request.getStoragePath())
                .addValue("moderationStatus", moderationStatus);

        List<MessageDto> inserted = jdbc.query(INSERT_MESSAGE_SQL, params, (rs, rowNum) -> mapMessageDto(rs));

        boolean isNew;
        MessageDto dto;
        if (inserted.isEmpty()) {
            dto = jdbc.queryForObject(FETCH_EXISTING_MESSAGE_SQL,
                    Map.of("callerId", callerId, "clientMessageId", request.getClientMessageId()),
                    (rs, rowNum) -> mapMessageDto(rs));
            isNew = false;
        } else {
            dto = inserted.get(0);
            isNew = true;
            // first_message_at and last_message_at are updated by the DB trigger touch_match_message_timestamps
            if ("APPROVED".equals(moderationStatus)) {
                notificationDispatcher.dispatchMessageNotification(recipientId, request.getMatchId(), null);
            }
        }

        return new MessageResult(dto, isNew);
    }

    @Transactional
    public void markRead(UUID callerId, UUID matchId) {
        MatchRow match = fetchAndValidateParticipant(matchId, callerId);
        String col = callerId.equals(match.userOneId()) ? "user_one_last_read_at" : "user_two_last_read_at";
        jdbc.update("UPDATE matches SET " + col + " = NOW() WHERE id = :matchId",
                Map.of("matchId", matchId));
    }

    @Transactional
    public boolean unmatch(UUID callerId, UUID matchId) {
        fetchAndValidateParticipant(matchId, callerId);
        return matchLifecycleService.endMatch(matchId, "USER_UNMATCH", callerId);
    }

    private MatchRow fetchAndValidateParticipant(UUID matchId, UUID callerId) {
        List<MatchRow> rows = jdbc.query(FETCH_MATCH_SQL, Map.of("matchId", matchId),
                (rs, rowNum) -> new MatchRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_one_id", UUID.class),
                        rs.getObject("user_two_id", UUID.class),
                        rs.getString("status")
                ));

        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "match_not_found");
        }
        MatchRow match = rows.get(0);
        if (!callerId.equals(match.userOneId()) && !callerId.equals(match.userTwoId())) {
            throw new MessagingException(403, "not_participant");
        }
        return match;
    }

    private String signedMediaUrl(String storageBucket, String storagePath) {
        return null;
    }

    private String determineModerationStatus(String body) {
        if (body == null) return "APPROVED";
        if (PHONE_PATTERN.matcher(body).find()
                || HANDLE_PATTERN.matcher(body).find()
                || MONEY_PATTERN.matcher(body).find()) {
            return "PENDING";
        }
        return "APPROVED";
    }

    private MessageDto mapMessageDto(ResultSet rs) throws SQLException {
        return new MessageDto(
                rs.getObject("id", UUID.class),
                rs.getObject("match_id", UUID.class),
                rs.getObject("sender_user_id", UUID.class),
                rs.getString("message_type"),
                rs.getString("body"),
                rs.getString("storage_bucket"),
                rs.getString("storage_path"),
                rs.getString("moderation_status"),
                rs.getObject("created_at", OffsetDateTime.class)
        );
    }
}
