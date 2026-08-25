package com.qaliye.backend.discovery.service;

import com.qaliye.backend.billing.repository.ActionLimitRepository;
import com.qaliye.backend.billing.service.ActionCostService;
import com.qaliye.backend.billing.service.CreditService;
import com.qaliye.backend.chat.repository.ChatMatchRepository;
import com.qaliye.backend.chat.repository.ChatMessageRepository;
import com.qaliye.backend.discovery.dto.MatchSummaryDto;
import com.qaliye.backend.discovery.dto.SuperMessageActionResponse;
import com.qaliye.backend.discovery.dto.SuperMessageResponse;
import com.qaliye.backend.discovery.dto.SwipeActionResponse;
import com.qaliye.backend.discovery.dto.UserProfileBrief;
import com.qaliye.backend.discovery.exception.ActionLimitExceededException;
import com.qaliye.backend.discovery.repository.DiscoveryActionRepository;
import com.qaliye.backend.notifications.NotificationDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class SuperMessageService {

    private static final Logger log = LoggerFactory.getLogger(SuperMessageService.class);

    private static final String INSERT_SQL = """
            INSERT INTO pre_match_messages
                (sender_id, receiver_id, message, action_type, credit_cost, status, idempotency_key)
            VALUES
                (:senderId, :receiverId, :message, 'SUPER_MESSAGE', :creditCost, 'SENT', :idempotencyKey)
            RETURNING id, created_at
            """;

    private static final String FIND_BY_IDEMPOTENCY_SQL = """
            SELECT pm.id, pm.sender_id, pm.receiver_id, pm.message, pm.action_type, pm.credit_cost,
                   pm.status, pm.viewed_at, pm.responded_at, pm.match_id, pm.created_at,
                   sp.display_name AS sender_name, spp.storage_bucket AS sender_bucket, spp.storage_path AS sender_path,
                   rp.display_name AS receiver_name, rpp.storage_bucket AS receiver_bucket, rpp.storage_path AS receiver_path,
                   (sau.status = 'DELETED') AS sender_deleted,
                   (rau.status = 'DELETED') AS receiver_deleted
            FROM pre_match_messages pm
            LEFT JOIN profiles sp ON sp.user_id = pm.sender_id
            LEFT JOIN profile_photos spp ON spp.user_id = pm.sender_id AND spp.is_primary = TRUE
                    AND spp.moderation_status = 'APPROVED' AND spp.deleted_at IS NULL
            LEFT JOIN app_users sau ON sau.id = pm.sender_id
            LEFT JOIN profiles rp ON rp.user_id = pm.receiver_id
            LEFT JOIN profile_photos rpp ON rpp.user_id = pm.receiver_id AND rpp.is_primary = TRUE
                    AND rpp.moderation_status = 'APPROVED' AND rpp.deleted_at IS NULL
            LEFT JOIN app_users rau ON rau.id = pm.receiver_id
            WHERE pm.sender_id = :senderId AND pm.idempotency_key = :idempotencyKey
            """;

    private static final String FIND_BY_ID_SQL = """
            SELECT pm.id, pm.sender_id, pm.receiver_id, pm.message, pm.action_type, pm.credit_cost,
                   pm.status, pm.viewed_at, pm.responded_at, pm.match_id, pm.created_at,
                   sp.display_name AS sender_name, spp.storage_bucket AS sender_bucket, spp.storage_path AS sender_path,
                   rp.display_name AS receiver_name, rpp.storage_bucket AS receiver_bucket, rpp.storage_path AS receiver_path,
                   (sau.status = 'DELETED') AS sender_deleted,
                   (rau.status = 'DELETED') AS receiver_deleted
            FROM pre_match_messages pm
            LEFT JOIN profiles sp ON sp.user_id = pm.sender_id
            LEFT JOIN profile_photos spp ON spp.user_id = pm.sender_id AND spp.is_primary = TRUE
                    AND spp.moderation_status = 'APPROVED' AND spp.deleted_at IS NULL
            LEFT JOIN app_users sau ON sau.id = pm.sender_id
            LEFT JOIN profiles rp ON rp.user_id = pm.receiver_id
            LEFT JOIN profile_photos rpp ON rpp.user_id = pm.receiver_id AND rpp.is_primary = TRUE
                    AND rpp.moderation_status = 'APPROVED' AND rpp.deleted_at IS NULL
            LEFT JOIN app_users rau ON rau.id = pm.receiver_id
            WHERE pm.id = :id
            """;

    private static final String LIST_AS_SENDER_SQL = """
            SELECT pm.id, pm.sender_id, pm.receiver_id, pm.message, pm.action_type, pm.credit_cost,
                   pm.status, pm.viewed_at, pm.responded_at, pm.match_id, pm.created_at,
                   sp.display_name AS sender_name, spp.storage_bucket AS sender_bucket, spp.storage_path AS sender_path,
                   rp.display_name AS receiver_name, rpp.storage_bucket AS receiver_bucket, rpp.storage_path AS receiver_path,
                   (sau.status = 'DELETED') AS sender_deleted,
                   (rau.status = 'DELETED') AS receiver_deleted
            FROM pre_match_messages pm
            LEFT JOIN profiles sp ON sp.user_id = pm.sender_id
            LEFT JOIN profile_photos spp ON spp.user_id = pm.sender_id AND spp.is_primary = TRUE
                    AND spp.moderation_status = 'APPROVED' AND spp.deleted_at IS NULL
            LEFT JOIN app_users sau ON sau.id = pm.sender_id
            LEFT JOIN profiles rp ON rp.user_id = pm.receiver_id
            LEFT JOIN profile_photos rpp ON rpp.user_id = pm.receiver_id AND rpp.is_primary = TRUE
                    AND rpp.moderation_status = 'APPROVED' AND rpp.deleted_at IS NULL
            JOIN app_users rau ON rau.id = pm.receiver_id
            WHERE pm.sender_id = :userId
              AND pm.status NOT IN ('ACCEPTED', 'PASSED')
              AND rau.status <> 'DELETED'
            ORDER BY pm.created_at DESC
            LIMIT :limit OFFSET :offset
            """;

    private static final String LIST_AS_RECEIVER_SQL = """
            SELECT pm.id, pm.sender_id, pm.receiver_id, pm.message, pm.action_type, pm.credit_cost,
                   pm.status, pm.viewed_at, pm.responded_at, pm.match_id, pm.created_at,
                   sp.display_name AS sender_name, spp.storage_bucket AS sender_bucket, spp.storage_path AS sender_path,
                   rp.display_name AS receiver_name, rpp.storage_bucket AS receiver_bucket, rpp.storage_path AS receiver_path,
                   (sau.status = 'DELETED') AS sender_deleted,
                   (rau.status = 'DELETED') AS receiver_deleted
            FROM pre_match_messages pm
            LEFT JOIN profiles sp ON sp.user_id = pm.sender_id
            LEFT JOIN profile_photos spp ON spp.user_id = pm.sender_id AND spp.is_primary = TRUE
                    AND spp.moderation_status = 'APPROVED' AND spp.deleted_at IS NULL
            JOIN app_users sau ON sau.id = pm.sender_id
            LEFT JOIN profiles rp ON rp.user_id = pm.receiver_id
            LEFT JOIN profile_photos rpp ON rpp.user_id = pm.receiver_id AND rpp.is_primary = TRUE
                    AND rpp.moderation_status = 'APPROVED' AND rpp.deleted_at IS NULL
            LEFT JOIN app_users rau ON rau.id = pm.receiver_id
            WHERE pm.receiver_id = :userId
              AND pm.status NOT IN ('ACCEPTED', 'PASSED')
              AND sau.status <> 'DELETED'
            ORDER BY pm.created_at DESC
            LIMIT :limit OFFSET :offset
            """;

    private static final String LINK_TO_ACTION_SQL = """
            UPDATE user_discovery_actions
            SET pre_match_message_id = :messageId
            WHERE id = :actionId
            """;

    private static final String MARK_VIEWED_SQL = """
            UPDATE pre_match_messages
            SET status = 'VIEWED', viewed_at = NOW(), updated_at = NOW()
            WHERE id = :id AND status = 'SENT'
            """;

    private static final String MARK_ACCEPTED_SQL = """
            UPDATE pre_match_messages
            SET status = 'ACCEPTED', responded_at = NOW(), updated_at = NOW(), match_id = :matchId
            WHERE id = :id AND status IN ('SENT', 'VIEWED')
            RETURNING responded_at
            """;

    private static final String MARK_PASSED_SQL = """
            UPDATE pre_match_messages
            SET status = 'PASSED', responded_at = NOW(), updated_at = NOW()
            WHERE id = :id AND status IN ('SENT', 'VIEWED')
            RETURNING responded_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ActionCostService actionCostService;
    private final CreditService creditService;
    private final DiscoveryActionRepository actionRepo;
    private final ActionLimitRepository actionLimitRepo;
    private final StorageSigningService signingService;
    private final SwipeActionService swipeActionService;
    private final NotificationDispatcher notificationDispatcher;
    private final ChatMatchRepository chatMatchRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final MatchService matchService;

    public SuperMessageService(NamedParameterJdbcTemplate jdbc,
                                ActionCostService actionCostService,
                                CreditService creditService,
                                DiscoveryActionRepository actionRepo,
                                ActionLimitRepository actionLimitRepo,
                                StorageSigningService signingService,
                                SwipeActionService swipeActionService,
                                NotificationDispatcher notificationDispatcher,
                                ChatMatchRepository chatMatchRepository,
                                ChatMessageRepository chatMessageRepository,
                                MatchService matchService) {
        this.jdbc = jdbc;
        this.actionCostService = actionCostService;
        this.creditService = creditService;
        this.actionRepo = actionRepo;
        this.actionLimitRepo = actionLimitRepo;
        this.signingService = signingService;
        this.swipeActionService = swipeActionService;
        this.notificationDispatcher = notificationDispatcher;
        this.chatMatchRepository = chatMatchRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.matchService = matchService;
    }

    @Transactional
    public SuperMessageResponse send(UUID senderId, UUID receiverId, String message, UUID idempotencyKey) {
        // Prevent self-message
        if (senderId.equals(receiverId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cannot_message_self");
        }

        // Idempotency: check existing by idempotency key
        if (idempotencyKey != null) {
            Optional<SuperMessageResponse> existing = findByidempotencyKey(senderId, idempotencyKey);
            if (existing.isPresent()) {
                log.debug("Super message idempotent return for sender={}, key={}", senderId, idempotencyKey);
                return existing.get();
            }
        }

        // Evaluate SUPER_MESSAGE action cost
        ActionCostService.ActionCostResult cost = actionCostService.evaluate(senderId, "SUPER_MESSAGE");

        if (cost.isBlocked()) {
            throw new ActionLimitExceededException("SUPER_MESSAGE", cost.periodType());
        }

        // Consume credits if required
        long creditCost = cost.creditCost();
        if (cost.requiresCredits()) {
            String idemKey = "supermsg-" + idempotencyKey;
            creditService.consumeCredits(senderId, creditCost, "SUPER_MESSAGE", idemKey);
        }

        // Increment limit tracker if applicable
        if (cost.ruleId() != null && cost.limitValue() != null) {
            actionLimitRepo.ensureExists(senderId, cost.ruleId(), cost.periodStart(), cost.periodEnd());
            actionLimitRepo.findForUpdate(senderId, cost.ruleId(), cost.periodStart())
                    .ifPresent(t -> actionLimitRepo.increment(t.id()));
        }

        // Insert pre_match_messages record
        Map<String, Object> row = jdbc.queryForMap(INSERT_SQL, new MapSqlParameterSource()
                .addValue("senderId", senderId)
                .addValue("receiverId", receiverId)
                .addValue("message", message)
                .addValue("creditCost", creditCost)
                .addValue("idempotencyKey", idempotencyKey != null ? idempotencyKey.toString() : UUID.randomUUID().toString()));

        UUID messageId = (UUID) row.get("id");
        Instant createdAt = toInstant(row.get("created_at"));

        // Auto-create LIKE discovery action (not charged separately)
        UUID clientActionId = idempotencyKey != null ? idempotencyKey : UUID.randomUUID();
        DiscoveryActionRepository.ActionRow action =
                actionRepo.insertAction(senderId, receiverId, "LIKE", clientActionId);

        // Link the discovery action to the super message
        jdbc.update(LINK_TO_ACTION_SQL, new MapSqlParameterSource()
                .addValue("messageId", messageId)
                .addValue("actionId", action.id()));

        log.info("Super message sent: sender={}, receiver={}, messageId={}, actionId={}, creditCost={}",
                senderId, receiverId, messageId, action.id(), creditCost);

        notificationDispatcher.dispatchSuperMessageNotification(senderId, receiverId, messageId);

        // Check for mutual like and create match if the receiver already liked the sender
        UUID lo = senderId.compareTo(receiverId) < 0 ? senderId : receiverId;
        UUID hi = senderId.compareTo(receiverId) < 0 ? receiverId : senderId;
        jdbc.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtext(:pairKey))",
                new MapSqlParameterSource("pairKey", lo.toString() + ":" + hi.toString()),
                Object.class);

        Optional<DiscoveryActionRepository.ActionRow> mutualAction =
                actionRepo.findMutualActiveLike(senderId, receiverId);
        if (mutualAction.isPresent()) {
            Optional<MatchSummaryDto> match = matchService.tryCreateMatch(
                    senderId, receiverId, action.id(), mutualAction.get().id());
            match.ifPresent(m -> {
                notificationDispatcher.dispatchMatchNotification(senderId, receiverId, m.matchId());
                log.info("Match created from super message: sender={}, receiver={}, matchId={}",
                        senderId, receiverId, m.matchId());
            });
        }

        UserProfileBrief senderBrief = fetchProfileBrief(senderId);
        UserProfileBrief receiverBrief = fetchProfileBrief(receiverId);

        return new SuperMessageResponse(
                messageId, senderId, receiverId, senderBrief, receiverBrief, message, "SUPER_MESSAGE",
                creditCost, "SENT", null, null, null, action.id(), createdAt
        );
    }

    public Optional<SuperMessageResponse> findById(UUID callerId, UUID messageId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                FIND_BY_ID_SQL, new MapSqlParameterSource("id", messageId));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        UUID senderId = (UUID) row.get("sender_id");
        UUID receiverId = (UUID) row.get("receiver_id");

        // Only sender or receiver can view
        if (!callerId.equals(senderId) && !callerId.equals(receiverId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "super_message_not_found");
        }

        // If receiver is viewing for the first time, mark as VIEWED
        if (callerId.equals(receiverId) && "SENT".equals(row.get("status"))) {
            jdbc.update(MARK_VIEWED_SQL, new MapSqlParameterSource("id", messageId));
            row.put("status", "VIEWED");
        }

        return Optional.of(mapRow(row));
    }

    public List<SuperMessageResponse> listSent(UUID senderId, int limit, int offset) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                LIST_AS_SENDER_SQL, new MapSqlParameterSource()
                        .addValue("userId", senderId)
                        .addValue("limit", limit)
                        .addValue("offset", offset));
        return rows.stream().map(this::mapRow).toList();
    }

    public List<SuperMessageResponse> listReceived(UUID receiverId, int limit, int offset) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                LIST_AS_RECEIVER_SQL, new MapSqlParameterSource()
                        .addValue("userId", receiverId)
                        .addValue("limit", limit)
                        .addValue("offset", offset));
        return rows.stream().map(this::mapRow).toList();
    }

    @Transactional
    public SuperMessageActionResponse accept(UUID receiverId, UUID messageId) {
        Map<String, Object> row = fetchMessageForReceiver(receiverId, messageId);
        UUID senderId = (UUID) row.get("sender_id");
        String currentStatus = (String) row.get("status");

        if (!"SENT".equals(currentStatus) && !"VIEWED".equals(currentStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "super_message_already_responded");
        }

        UUID clientActionId = UUID.randomUUID();
        SwipeActionResponse likeResponse = swipeActionService.recordLike(receiverId, senderId, clientActionId);

        UUID matchId = likeResponse.match() != null ? likeResponse.match().matchId() : null;
        Instant matchedAt = likeResponse.match() != null ? likeResponse.match().matchedAt() : null;

        // If a match was created, copy the super message text as the first chat message
        if (matchId != null) {
            String superMessageText = (String) row.get("message");
            long sequenceNumber = chatMatchRepository.reserveAndIncrementSequence(matchId);
            chatMessageRepository.insert(
                    matchId, senderId, UUID.randomUUID(), "TEXT", superMessageText, sequenceNumber);
        }

        List<java.sql.Timestamp> respondedRows = jdbc.query(
                MARK_ACCEPTED_SQL,
                new MapSqlParameterSource()
                        .addValue("id", messageId)
                        .addValue("matchId", matchId),
                (rs, rn) -> rs.getTimestamp("responded_at"));
        if (respondedRows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "super_message_already_responded");
        }
        Instant respondedAt = toInstant(respondedRows.get(0));

        if (matchId != null) {
            notificationDispatcher.dispatchMatchNotification(receiverId, senderId, matchId);
        }

        log.info("Super message accepted: receiver={}, sender={}, messageId={}, matchId={}",
                receiverId, senderId, messageId, matchId);

        return new SuperMessageActionResponse(messageId, "ACCEPTED", respondedAt,
                matchId != null, matchId, matchedAt);
    }

    @Transactional
    public SuperMessageActionResponse pass(UUID receiverId, UUID messageId) {
        Map<String, Object> row = fetchMessageForReceiver(receiverId, messageId);
        UUID senderId = (UUID) row.get("sender_id");
        String currentStatus = (String) row.get("status");

        if (!"SENT".equals(currentStatus) && !"VIEWED".equals(currentStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "super_message_already_responded");
        }

        UUID clientActionId = UUID.randomUUID();
        swipeActionService.recordPass(receiverId, senderId, clientActionId);

        List<java.sql.Timestamp> respondedRows = jdbc.query(
                MARK_PASSED_SQL,
                new MapSqlParameterSource().addValue("id", messageId),
                (rs, rn) -> rs.getTimestamp("responded_at"));
        if (respondedRows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "super_message_already_responded");
        }
        Instant respondedAt = toInstant(respondedRows.get(0));

        log.info("Super message passed: receiver={}, sender={}, messageId={}",
                receiverId, senderId, messageId);

        return new SuperMessageActionResponse(messageId, "PASSED", respondedAt,
                false, null, null);
    }

    private Map<String, Object> fetchMessageForReceiver(UUID receiverId, UUID messageId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                FIND_BY_ID_SQL, new MapSqlParameterSource("id", messageId));
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "super_message_not_found");
        }
        Map<String, Object> row = rows.get(0);
        UUID msgReceiverId = (UUID) row.get("receiver_id");
        if (!receiverId.equals(msgReceiverId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "super_message_not_found");
        }
        return row;
    }

    private Optional<SuperMessageResponse> findByidempotencyKey(UUID senderId, UUID idempotencyKey) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                FIND_BY_IDEMPOTENCY_SQL, new MapSqlParameterSource()
                        .addValue("senderId", senderId)
                        .addValue("idempotencyKey", idempotencyKey.toString()));
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        Map<String, Object> row = rows.get(0);
        UUID messageId = (UUID) row.get("id");

        // Find the linked discovery action
        String findActionSql = """
                SELECT id FROM user_discovery_actions
                WHERE pre_match_message_id = :messageId
                LIMIT 1
                """;
        List<UUID> actionIds = jdbc.query(findActionSql,
                new MapSqlParameterSource("messageId", messageId),
                (rs, rn) -> rs.getObject("id", UUID.class));
        UUID actionId = actionIds.isEmpty() ? null : actionIds.get(0);

        return Optional.of(mapRow(row, actionId));
    }

    private SuperMessageResponse mapRow(Map<String, Object> row) {
        return mapRow(row, null);
    }

    private SuperMessageResponse mapRow(Map<String, Object> row, UUID actionId) {
        UUID id = (UUID) row.get("id");
        UUID senderId = (UUID) row.get("sender_id");
        UUID receiverId = (UUID) row.get("receiver_id");
        String message = (String) row.get("message");
        String actionType = (String) row.get("action_type");
        long creditCost = row.get("credit_cost") != null
                ? ((Number) row.get("credit_cost")).longValue() : 0L;
        String status = (String) row.get("status");
        UUID matchId = (UUID) row.get("match_id");

        Instant viewedAt = toInstant(row.get("viewed_at"));
        Instant respondedAt = toInstant(row.get("responded_at"));
        Instant createdAt = toInstant(row.get("created_at"));

        boolean senderDeleted = booleanFromRow(row, "sender_deleted");
        boolean receiverDeleted = booleanFromRow(row, "receiver_deleted");

        UserProfileBrief senderBrief = buildProfileBrief(senderId,
                (String) row.get("sender_name"),
                (String) row.get("sender_bucket"),
                (String) row.get("sender_path"),
                senderDeleted);
        UserProfileBrief receiverBrief = buildProfileBrief(receiverId,
                (String) row.get("receiver_name"),
                (String) row.get("receiver_bucket"),
                (String) row.get("receiver_path"),
                receiverDeleted);

        return new SuperMessageResponse(
                id, senderId, receiverId, senderBrief, receiverBrief, message, actionType,
                creditCost, status, viewedAt, respondedAt, matchId,
                actionId, createdAt
        );
    }

    private Instant toInstant(Object obj) {
        if (obj == null) return null;
        if (obj instanceof OffsetDateTime odt) return odt.toInstant();
        if (obj instanceof java.sql.Timestamp ts) return ts.toInstant();
        return null;
    }

    private boolean booleanFromRow(Map<String, Object> row, String key) {
        Object val = row.get(key);
        if (val == null) return false;
        if (val instanceof Boolean b) return b;
        if (val instanceof Number n) return n.intValue() != 0;
        return false;
    }

    private UserProfileBrief buildProfileBrief(UUID userId, String displayName,
                                                String bucket, String path,
                                                boolean deleted) {
        String photoUrl = null;
        if (bucket != null && path != null) {
            photoUrl = signingService.sign(bucket, path);
        }
        return new UserProfileBrief(userId, displayName, photoUrl, deleted);
    }

    private static final String FETCH_PROFILE_BRIEF_SQL = """
            SELECT p.display_name, pp.storage_bucket, pp.storage_path,
                   (au.status = 'DELETED') AS deleted
            FROM profiles p
            LEFT JOIN profile_photos pp ON pp.user_id = p.user_id AND pp.is_primary = TRUE
                    AND pp.moderation_status = 'APPROVED' AND pp.deleted_at IS NULL
            LEFT JOIN app_users au ON au.id = p.user_id
            WHERE p.user_id = :userId
            """;

    private UserProfileBrief fetchProfileBrief(UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                FETCH_PROFILE_BRIEF_SQL, new MapSqlParameterSource("userId", userId));
        if (rows.isEmpty()) {
            return new UserProfileBrief(userId, null, null, false);
        }
        Map<String, Object> row = rows.get(0);
        return buildProfileBrief(userId,
                (String) row.get("display_name"),
                (String) row.get("storage_bucket"),
                (String) row.get("storage_path"),
                booleanFromRow(row, "deleted"));
    }
}
