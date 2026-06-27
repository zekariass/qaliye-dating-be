package com.qaliye.backend.chat.service;

import com.qaliye.backend.activity.ActivityStatus;
import com.qaliye.backend.activity.ActivityStatusService;
import com.qaliye.backend.chat.cursor.ChatCursorCodec;
import com.qaliye.backend.chat.cursor.ChatCursorCodec.CursorState;
import com.qaliye.backend.chat.dto.*;
import com.qaliye.backend.chat.repository.ChatMatchRepository;
import com.qaliye.backend.chat.repository.ChatMessageRepository;
import com.qaliye.backend.chat.repository.ChatNotificationSettingsRepository;
import com.qaliye.backend.discovery.service.StorageSigningService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ChatQueryService {

    private static final int MIN_LIMIT = 1;
    private static final int MAX_INBOX_LIMIT = 50;
    private static final int DEFAULT_INBOX_LIMIT = 25;
    private static final int MAX_MSG_LIMIT = 100;
    private static final int DEFAULT_MSG_LIMIT = 50;

    private final MatchAuthorizationService authorizationService;
    private final ChatMatchRepository matchRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatNotificationSettingsRepository notifSettingsRepo;
    private final ChatCursorCodec cursorCodec;
    private final ChatDtoMapper mapper;
    private final StorageSigningService signingService;
    private final NamedParameterJdbcTemplate jdbc;
    private final ActivityStatusService activityStatusService;

    public ChatQueryService(MatchAuthorizationService authorizationService,
                             ChatMatchRepository matchRepository,
                             ChatMessageRepository messageRepository,
                             ChatNotificationSettingsRepository notifSettingsRepo,
                             ChatCursorCodec cursorCodec,
                             ChatDtoMapper mapper,
                             StorageSigningService signingService,
                             NamedParameterJdbcTemplate jdbc,
                             ActivityStatusService activityStatusService) {
        this.authorizationService = authorizationService;
        this.matchRepository = matchRepository;
        this.messageRepository = messageRepository;
        this.notifSettingsRepo = notifSettingsRepo;
        this.cursorCodec = cursorCodec;
        this.mapper = mapper;
        this.signingService = signingService;
        this.jdbc = jdbc;
        this.activityStatusService = activityStatusService;
    }

    private static final String INBOX_BASE_SQL = """
            SELECT
                m.id                                                AS match_id,
                m.matched_at,
                m.last_message_at,
                CASE WHEN m.user_one_id = :userId THEN m.user_two_id ELSE m.user_one_id END AS other_user_id,
                CASE WHEN m.user_one_id = :userId THEN m.user_one_last_read_sequence
                     ELSE m.user_two_last_read_sequence END          AS my_read_seq,
                p.display_name,
                p.is_verified,
                pp.storage_bucket,
                pp.storage_path,
                au.last_active_at,
                au.show_activity_status
            FROM matches m
            JOIN profiles p ON p.user_id = CASE WHEN m.user_one_id = :userId
                                                THEN m.user_two_id ELSE m.user_one_id END
            JOIN app_users au ON au.id = CASE WHEN m.user_one_id = :userId
                                             THEN m.user_two_id ELSE m.user_one_id END
            LEFT JOIN profile_photos pp
                ON pp.user_id = CASE WHEN m.user_one_id = :userId
                                     THEN m.user_two_id ELSE m.user_one_id END
               AND pp.is_primary = TRUE
               AND pp.moderation_status = 'APPROVED'
               AND pp.deleted_at IS NULL
            WHERE (m.user_one_id = :userId OR m.user_two_id = :userId)
              AND m.status = 'ACTIVE'
            """;

    private static final String UNREAD_FILTER_SQL = """
              AND EXISTS (
                  SELECT 1 FROM messages msg
                  WHERE msg.match_id = m.id
                    AND msg.sender_user_id <> :userId
                    AND msg.sequence_number > (
                        CASE WHEN m.user_one_id = :userId THEN m.user_one_last_read_sequence
                             ELSE m.user_two_last_read_sequence END
                    )
                    AND msg.deleted_at IS NULL
                    AND msg.moderation_status = 'APPROVED'
              )
            """;

    private static final String CURSOR_SQL = """
              AND (
                  m.last_message_at < :cursorLastMessageAt
                  OR (m.last_message_at IS NULL AND :cursorLastMessageAt IS NULL
                      AND (m.matched_at < :cursorMatchedAt
                           OR (m.matched_at = :cursorMatchedAt AND m.id < :cursorMatchId)))
                  OR (m.last_message_at = :cursorLastMessageAt
                      AND (m.matched_at < :cursorMatchedAt
                           OR (m.matched_at = :cursorMatchedAt AND m.id < :cursorMatchId)))
              )
            """;

    private static final String INBOX_ORDER_SQL = """
            ORDER BY m.last_message_at DESC NULLS LAST, m.matched_at DESC, m.id DESC
            LIMIT :limit
            """;

    @Transactional(readOnly = true)
    public InboxResponse getInbox(UUID callerId, String filter, String cursorToken, int limitParam) {
        authorizationService.requireActiveAccount(callerId);

        String resolvedFilter = "UNREAD".equalsIgnoreCase(filter) ? "UNREAD" : "ALL";
        int limit = Math.min(Math.max(limitParam, MIN_LIMIT), MAX_INBOX_LIMIT);
        boolean filterUnread = "UNREAD".equals(resolvedFilter);

        CursorState cursor = null;
        if (cursorToken != null && !cursorToken.isBlank()) {
            cursor = cursorCodec.decode(cursorToken, resolvedFilter);
        }

        StringBuilder sql = new StringBuilder(INBOX_BASE_SQL);
        if (filterUnread) sql.append(UNREAD_FILTER_SQL);
        if (cursor != null) sql.append(CURSOR_SQL);
        sql.append(INBOX_ORDER_SQL);

        var params = new MapSqlParameterSource()
                .addValue("userId", callerId, Types.OTHER)
                .addValue("limit", limit + 1);
        if (cursor != null) {
            params.addValue("cursorLastMessageAt",
                    toOffsetDateTime(cursor.lastMessageAt()), Types.TIMESTAMP_WITH_TIMEZONE);
            params.addValue("cursorMatchedAt",
                    toOffsetDateTime(cursor.matchedAt()), Types.TIMESTAMP_WITH_TIMEZONE);
            params.addValue("cursorMatchId", cursor.matchId(), Types.OTHER);
        }

        Instant capturedNow = activityStatusService.now();

        List<InboxItemDto> items = new ArrayList<>();
        jdbc.query(sql.toString(), params, rs -> {
            UUID matchId   = rs.getObject("match_id", UUID.class);
            UUID otherUserId = rs.getObject("other_user_id", UUID.class);
            long myReadSeq = rs.getLong("my_read_seq");
            OffsetDateTime matchedAt = rs.getObject("matched_at", OffsetDateTime.class);
            OffsetDateTime lastMsgAt = rs.getObject("last_message_at", OffsetDateTime.class);

            String bucket = rs.getString("storage_bucket");
            String path   = rs.getString("storage_path");
            String avatarUrl = (bucket != null && path != null) ? signingService.sign(bucket, path) : null;

            OffsetDateTime lastActiveAt = rs.getObject("last_active_at", OffsetDateTime.class);
            boolean showActivityStatus = rs.getBoolean("show_activity_status");
            ActivityStatus activityStatus = activityStatusService.resolve(showActivityStatus, lastActiveAt, capturedNow);

            ParticipantDto participant = new ParticipantDto(
                    otherUserId,
                    rs.getString("display_name"),
                    avatarUrl,
                    rs.getBoolean("is_verified"),
                    activityStatus);

            ChatMessageRepository.MessageRow lastMsg = messageRepository.getLastMessage(matchId).orElse(null);
            LastMessageDto lastMessageDto = lastMsg != null ? toLastMessageDto(lastMsg) : null;

            int unread = (int) messageRepository.countUnread(matchId, callerId, myReadSeq);

            var muteSettings = notifSettingsRepo.find(matchId, callerId);
            OffsetDateTime mutedUntil = muteSettings.map(s -> s.mutedUntil()).orElse(null);

            items.add(new InboxItemDto(
                    matchId, "ACTIVE", participant, lastMessageDto,
                    unread, toInstant(mutedUntil), toInstant(matchedAt), toInstant(lastMsgAt)));
        });

        boolean hasMore = items.size() > limit;
        List<InboxItemDto> page = hasMore ? items.subList(0, limit) : items;

        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            InboxItemDto last = page.get(page.size() - 1);
            nextCursor = cursorCodec.encode(new CursorState(
                    resolvedFilter,
                    Instant.now(),
                    last.lastMessageAt(),
                    last.matchedAt(),
                    last.matchId()));
        }

        return new InboxResponse(page, nextCursor);
    }

    @Transactional(readOnly = true)
    public ChatMatchMetadataDto getMatchMetadata(UUID callerId, UUID matchId) {
        MatchAuthorizationService.MatchContext ctx = authorizationService.authorize(callerId, matchId);
        ChatMatchRepository.MatchRow match = matchRepository.findById(matchId).orElseThrow(
                com.qaliye.backend.chat.exception.MatchNotFoundException::new);

        UUID otherUserId = ctx.otherUserId();
        ParticipantDto participant = loadParticipant(otherUserId);

        ReceiptStateDto receiptState = new ReceiptStateDto(
                ctx.myLastDeliveredSequence(),
                ctx.myLastReadSequence(),
                ctx.theirLastDeliveredSequence(),
                ctx.theirLastReadSequence());

        return new ChatMatchMetadataDto(matchId, match.status(), participant, receiptState);
    }

    @Transactional(readOnly = true)
    public MessagesResponse getMessages(UUID callerId, UUID matchId,
                                        Long beforeSequence, Long afterSequence, int limitParam) {
        MatchAuthorizationService.MatchContext ctx = authorizationService.authorize(callerId, matchId);
        int limit = Math.min(Math.max(limitParam, MIN_LIMIT), MAX_MSG_LIMIT);

        if (beforeSequence != null && afterSequence != null) {
            throw new com.qaliye.backend.chat.exception.InvalidMessageException(
                    "Only one of beforeSequence or afterSequence is allowed.");
        }

        long theirDelivered = ctx.theirLastDeliveredSequence();
        long theirRead      = ctx.theirLastReadSequence();

        List<ChatMessageRepository.MessageRow> rawRows;
        if (beforeSequence != null) {
            if (beforeSequence <= 0) throw new com.qaliye.backend.chat.exception.InvalidCursorException();
            rawRows = messageRepository.getMessagesBefore(matchId, beforeSequence, limit);
        } else if (afterSequence != null) {
            if (afterSequence < 0) throw new com.qaliye.backend.chat.exception.InvalidCursorException();
            rawRows = messageRepository.getMessagesAfter(matchId, afterSequence, limit);
        } else {
            rawRows = messageRepository.getLatestMessages(matchId, limit);
        }

        boolean hasMore = rawRows.size() > limit;
        List<ChatMessageRepository.MessageRow> page = hasMore ? rawRows.subList(0, limit) : rawRows;

        List<ChatMessageDto> dtos = page.stream()
                .map(r -> mapper.toMessageDto(r, theirRead, theirDelivered, callerId))
                .toList();

        ActivityStatus participantActivityStatus = loadParticipantActivityStatus(ctx.otherUserId());

        return new MessagesResponse(matchId, participantActivityStatus, dtos, hasMore);
    }

    private ParticipantDto loadParticipant(UUID userId) {
        String sql = """
                SELECT p.display_name, p.is_verified,
                       pp.storage_bucket, pp.storage_path,
                       au.last_active_at, au.show_activity_status
                FROM profiles p
                JOIN app_users au ON au.id = p.user_id
                LEFT JOIN profile_photos pp
                       ON pp.user_id = p.user_id
                      AND pp.is_primary = TRUE
                      AND pp.moderation_status = 'APPROVED'
                      AND pp.deleted_at IS NULL
                WHERE p.user_id = :userId
                """;
        Instant now = activityStatusService.now();
        return jdbc.query(sql, new MapSqlParameterSource("userId", userId), rs -> {
            if (!rs.next()) return new ParticipantDto(userId, "Unknown", null, false, ActivityStatus.OFFLINE);
            String bucket = rs.getString("storage_bucket");
            String path   = rs.getString("storage_path");
            String avatarUrl = (bucket != null && path != null) ? signingService.sign(bucket, path) : null;
            OffsetDateTime lastActiveAt = rs.getObject("last_active_at", OffsetDateTime.class);
            boolean showActivity = rs.getBoolean("show_activity_status");
            ActivityStatus activityStatus = activityStatusService.resolve(showActivity, lastActiveAt, now);
            return new ParticipantDto(userId, rs.getString("display_name"), avatarUrl,
                    rs.getBoolean("is_verified"), activityStatus);
        });
    }

    private ActivityStatus loadParticipantActivityStatus(UUID userId) {
        return jdbc.query(
                "SELECT last_active_at, show_activity_status FROM app_users WHERE id = :userId",
                new MapSqlParameterSource("userId", userId),
                rs -> {
                    if (!rs.next()) return ActivityStatus.OFFLINE;
                    OffsetDateTime lastActiveAt = rs.getObject("last_active_at", OffsetDateTime.class);
                    boolean showActivity = rs.getBoolean("show_activity_status");
                    return activityStatusService.resolve(showActivity, lastActiveAt, activityStatusService.now());
                });
    }

    private LastMessageDto toLastMessageDto(ChatMessageRepository.MessageRow row) {
        String preview = row.body() != null && row.body().length() > 100
                ? row.body().substring(0, 100) + "…" : row.body();
        return new LastMessageDto(
                row.id(), row.sequenceNumber(), row.senderUserId(),
                row.messageType(), preview, toInstant(row.createdAt()));
    }

    private Instant toInstant(OffsetDateTime odt) {
        return odt != null ? odt.toInstant() : null;
    }

    private OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant != null ? instant.atOffset(java.time.ZoneOffset.UTC) : null;
    }
}
