package com.qaliye.backend.verification;

import com.qaliye.backend.notifications.NotificationDispatcher;
import com.qaliye.backend.storage.SupabaseStorageService;
import com.qaliye.backend.user.UserStatusService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class VerificationService {

    private static final String BUCKET = "verification-selfies";

    private static final String COUNT_APPROVED_PHOTOS_SQL = """
            SELECT COUNT(*) FROM profile_photos
            WHERE user_id = :callerId AND moderation_status = 'APPROVED'
            """;

    private static final String CHECK_PENDING_SQL = """
            SELECT id FROM user_verifications
            WHERE user_id = :callerId AND status = 'PENDING' LIMIT 1
            """;

    private static final String INSERT_VERIFICATION_SQL = """
            INSERT INTO user_verifications
                (user_id, verification_type, provider, storage_path, status, metadata)
            VALUES
                (:callerId, 'SELFIE_MATCH', 'MANUAL_ADMIN', :storagePath, 'PENDING', :metadata::jsonb)
            RETURNING id
            """;

    private static final String AUDIT_LOG_SQL = """
            INSERT INTO audit_log (actor_user_id, action, target_table, target_id, details)
            VALUES (:actorId, :action, :targetTable, :targetId, :details::jsonb)
            """;

    private static final String QUEUE_SQL = """
            SELECT uv.id, uv.user_id, uv.storage_path, uv.submitted_at,
                   p.display_name
            FROM user_verifications uv
            JOIN profiles p ON p.user_id = uv.user_id
            WHERE uv.status = :status
            ORDER BY uv.submitted_at ASC
            LIMIT 50
            """;

    private static final String BATCH_PHOTOS_SQL = """
            SELECT user_id, image_url FROM profile_photos
            WHERE user_id IN (:userIds) AND moderation_status = 'APPROVED'
            ORDER BY user_id, photo_order ASC
            """;

    private static final String PHOTOS_FOR_USER_SQL = """
            SELECT image_url FROM profile_photos
            WHERE user_id = :userId AND moderation_status = 'APPROVED'
            ORDER BY photo_order ASC LIMIT 6
            """;

    private static final String FETCH_VERIFICATION_SQL = """
            SELECT id, user_id, status FROM user_verifications
            WHERE id = :verificationId FOR UPDATE
            """;

    private static final String UPDATE_VERIFICATION_SQL = """
            UPDATE user_verifications
            SET status = :decision,
                reviewed_by = :moderatorId,
                reviewed_at = NOW(),
                rejection_reason = :rejectionReason
            WHERE id = :verificationId
            """;

    private static final String APPROVE_PROFILE_SQL =
            "UPDATE profiles SET is_verified = TRUE WHERE user_id = :userId";

    private final NamedParameterJdbcTemplate jdbc;
    private final UserStatusService userStatusService;
    private final SupabaseStorageService storageService;
    private final NotificationDispatcher notificationDispatcher;

    public VerificationService(NamedParameterJdbcTemplate jdbc,
                               UserStatusService userStatusService,
                               SupabaseStorageService storageService,
                               NotificationDispatcher notificationDispatcher) {
        this.jdbc = jdbc;
        this.userStatusService = userStatusService;
        this.storageService = storageService;
        this.notificationDispatcher = notificationDispatcher;
    }

    @Transactional
    public UUID submitVerification(UUID callerId, String storagePath) {
        Long approvedPhotoCount = jdbc.queryForObject(
                COUNT_APPROVED_PHOTOS_SQL, Map.of("callerId", callerId), Long.class);
        if (approvedPhotoCount == null || approvedPhotoCount == 0) {
            throw new VerificationException(400, "no_approved_photo",
                    "You must have at least one approved profile photo before submitting for verification.");
        }

        List<UUID> pending = jdbc.query(CHECK_PENDING_SQL, Map.of("callerId", callerId),
                (rs, rowNum) -> rs.getObject("id", UUID.class));
        if (!pending.isEmpty()) {
            throw new VerificationException(409, "verification_pending",
                    "A verification request is already under review.");
        }

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("callerId", callerId)
                .addValue("storagePath", storagePath)
                .addValue("metadata", "{}");

        List<UUID> ids = jdbc.query(INSERT_VERIFICATION_SQL, params,
                (rs, rowNum) -> rs.getObject("id", UUID.class));
        UUID verificationId = ids.get(0);

        writeAuditLog(callerId, "VERIFICATION_SUBMITTED", "user_verifications", verificationId, "{}");

        return verificationId;
    }

    public List<VerificationQueueItemDto> getQueue(UUID moderatorId, String status) {
        requireModeratorRole(moderatorId);

        List<Map<String, Object>> rows = jdbc.queryForList(QUEUE_SQL, Map.of("status", status));
        if (rows.isEmpty()) return List.of();

        List<UUID> userIds = rows.stream()
                .map(r -> (UUID) r.get("user_id"))
                .collect(Collectors.toList());

        Map<UUID, List<String>> photosByUser = fetchBatchPhotos(userIds);

        List<VerificationQueueItemDto> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            UUID verificationId = (UUID) row.get("id");
            UUID userId = (UUID) row.get("user_id");
            String storagePath = (String) row.get("storage_path");
            OffsetDateTime submittedAt = toOffsetDateTime(row.get("submitted_at"));
            String displayName = (String) row.get("display_name");

            String signedUrl = storagePath != null
                    ? storageService.generateSignedUrl(BUCKET, storagePath, 600)
                    : null;

            items.add(new VerificationQueueItemDto(
                    verificationId,
                    userId,
                    displayName,
                    submittedAt,
                    signedUrl,
                    photosByUser.getOrDefault(userId, List.of())
            ));
        }
        return items;
    }

    @Transactional
    public Map<String, Object> reviewVerification(UUID moderatorId, UUID verificationId,
                                                   ReviewVerificationRequest request) {
        requireModeratorRole(moderatorId);

        List<Map<String, Object>> rows = jdbc.queryForList(
                FETCH_VERIFICATION_SQL, Map.of("verificationId", verificationId));
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "verification_not_found");
        }
        Map<String, Object> ver = rows.get(0);
        String currentStatus = (String) ver.get("status");
        UUID targetUserId = (UUID) ver.get("user_id");

        if (!"PENDING".equals(currentStatus)) {
            throw new VerificationException(400, "already_reviewed");
        }

        String decision = request.getDecision();
        String rejectionReason = request.getRejectionReason();

        if ("REJECTED".equals(decision)
                && (rejectionReason == null || rejectionReason.isBlank())) {
            throw new VerificationException(400, "reason_required");
        }

        jdbc.update(UPDATE_VERIFICATION_SQL, new MapSqlParameterSource()
                .addValue("decision", decision)
                .addValue("moderatorId", moderatorId)
                .addValue("rejectionReason", rejectionReason)
                .addValue("verificationId", verificationId));

        if ("APPROVED".equals(decision)) {
            jdbc.update(APPROVE_PROFILE_SQL, Map.of("userId", targetUserId));
            notificationDispatcher.dispatchVerificationApprovedNotification(targetUserId);
        } else {
            notificationDispatcher.dispatchVerificationRejectedNotification(targetUserId, rejectionReason);
        }

        String auditDetails = "{\"decision\": \"" + decision + "\""
                + (rejectionReason != null ? ", \"rejection_reason\": \"" + rejectionReason + "\"" : "")
                + "}";
        writeAuditLog(moderatorId, "VERIFICATION_REVIEWED", "user_verifications", verificationId, auditDetails);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("verification_id", verificationId);
        result.put("status", decision);
        return result;
    }

    private void requireModeratorRole(UUID callerId) {
        var status = userStatusService.getStatus(callerId);
        if (status == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "access_denied");
        }
        String role = status.role();
        if (!"MODERATOR".equals(role) && !"ADMIN".equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "access_denied");
        }
    }

    private Map<UUID, List<String>> fetchBatchPhotos(List<UUID> userIds) {
        Map<UUID, List<String>> result = new LinkedHashMap<>();
        jdbc.query(BATCH_PHOTOS_SQL, Map.of("userIds", userIds), rs -> {
            UUID userId = rs.getObject("user_id", UUID.class);
            List<String> urls = result.computeIfAbsent(userId, k -> new ArrayList<>());
            if (urls.size() < 6) {
                urls.add(rs.getString("image_url"));
            }
        });
        return result;
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

    private OffsetDateTime toOffsetDateTime(Object value) {
        if (value instanceof OffsetDateTime odt) return odt;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
        return null;
    }
}
