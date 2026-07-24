package com.qaliye.backend.admin;

import com.qaliye.backend.moderation.PhotoModerationItemDto;
import com.qaliye.backend.storage.SupabaseStorageService;
import com.qaliye.backend.user.UserStatusService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AdminModerationQueueService {

    private static final String MANUAL_REVIEW_QUEUE_SQL = """
            SELECT pp.id, pp.user_id, pp.storage_bucket, pp.storage_path, pp.moderation_status,
                   pp.created_at, pp.rejection_reason, p.display_name,
                   imr.selected_face_confidence, imr.moderation_labels, imr.status AS imr_status
            FROM profile_photos pp
            JOIN profiles p ON p.user_id = pp.user_id
            LEFT JOIN image_moderation_results imr ON imr.image_id = pp.id
            WHERE pp.moderation_status = 'MANUAL_REVIEW'
              AND pp.deleted_at IS NULL
            ORDER BY pp.created_at ASC
            LIMIT 100
            """;

    private static final String REVIEW_QUEUE_SQL = """
            SELECT pp.id, pp.user_id, pp.storage_bucket, pp.storage_path, pp.moderation_status,
                   pp.created_at, pp.rejection_reason, p.display_name,
                   imr.selected_face_confidence, imr.moderation_labels, imr.status AS imr_status
            FROM profile_photos pp
            JOIN profiles p ON p.user_id = pp.user_id
            LEFT JOIN image_moderation_results imr ON imr.image_id = pp.id
            WHERE pp.moderation_status IN ('PENDING', 'MANUAL_REVIEW')
              AND pp.deleted_at IS NULL
            ORDER BY
                CASE pp.moderation_status
                    WHEN 'MANUAL_REVIEW' THEN 0
                    WHEN 'PENDING' THEN 1
                END,
                pp.created_at ASC
            LIMIT 100
            """;

    private static final String QUEUE_COUNTS_SQL = """
            SELECT
                COUNT(*) FILTER (WHERE moderation_status = 'PENDING') AS pending,
                COUNT(*) FILTER (WHERE moderation_status = 'MANUAL_REVIEW') AS manual_review,
                COUNT(*) FILTER (WHERE moderation_status = 'APPROVED') AS approved,
                COUNT(*) FILTER (WHERE moderation_status = 'REJECTED') AS rejected
            FROM profile_photos
            WHERE deleted_at IS NULL
            """;

    private static final String FIND_PHOTO_SQL = """
            SELECT id, user_id, moderation_status, deleted_at
            FROM profile_photos
            WHERE id = :photoId
            """;

    private static final String APPROVE_PHOTO_SQL = """
            UPDATE profile_photos
            SET moderation_status = 'APPROVED',
                reviewed_by       = :reviewerId,
                reviewed_at       = NOW(),
                rejection_reason  = NULL,
                updated_at        = NOW()
            WHERE id = :photoId
              AND deleted_at IS NULL
              AND moderation_status IN ('PENDING', 'MANUAL_REVIEW')
            """;

    private static final String REJECT_PHOTO_SQL = """
            UPDATE profile_photos
            SET moderation_status = 'REJECTED',
                reviewed_by       = :reviewerId,
                reviewed_at       = NOW(),
                rejection_reason  = :reason,
                updated_at        = NOW()
            WHERE id = :photoId
              AND deleted_at IS NULL
              AND moderation_status IN ('PENDING', 'MANUAL_REVIEW')
            """;

    private static final String AUDIT_LOG_SQL = """
            INSERT INTO audit_log (actor_user_id, action, target_table, target_id, details)
            VALUES (:actorId, :action, :targetTable, :targetId, :details::jsonb)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final UserStatusService userStatusService;
    private final SupabaseStorageService storageService;

    public AdminModerationQueueService(NamedParameterJdbcTemplate jdbc,
                                       UserStatusService userStatusService,
                                       SupabaseStorageService storageService) {
        this.jdbc = jdbc;
        this.userStatusService = userStatusService;
        this.storageService = storageService;
    }

    public List<PhotoModerationItemDto> getManualReviewQueue(UUID callerId) {
        requireModeratorRole(callerId);
        List<PhotoModerationItemDto> items = new ArrayList<>();
        jdbc.query(MANUAL_REVIEW_QUEUE_SQL, Map.of(), rs -> {
            items.add(new PhotoModerationItemDto(
                    rs.getObject("id", UUID.class),
                    rs.getObject("user_id", UUID.class),
                    storageService.generateSignedUrl(
                            rs.getString("storage_bucket"),
                            rs.getString("storage_path"), 3600),
                    rs.getString("moderation_status"),
                    toOffsetDateTime(rs.getTimestamp("created_at")),
                    rs.getString("display_name")
            ));
        });
        return items;
    }

    public List<PhotoModerationItemDto> getReviewQueue(UUID callerId) {
        requireModeratorRole(callerId);
        List<PhotoModerationItemDto> items = new ArrayList<>();
        jdbc.query(REVIEW_QUEUE_SQL, Map.of(), rs -> {
            items.add(new PhotoModerationItemDto(
                    rs.getObject("id", UUID.class),
                    rs.getObject("user_id", UUID.class),
                    storageService.generateSignedUrl(
                            rs.getString("storage_bucket"),
                            rs.getString("storage_path"), 3600),
                    rs.getString("moderation_status"),
                    toOffsetDateTime(rs.getTimestamp("created_at")),
                    rs.getString("display_name")
            ));
        });
        return items;
    }

    public java.util.Map<String, Object> getQueueCounts(UUID callerId) {
        requireModeratorRole(callerId);
        var rows = jdbc.queryForList(QUEUE_COUNTS_SQL, Map.of());
        if (rows.isEmpty()) {
            return java.util.Map.of("pending", 0, "manual_review", 0, "approved", 0, "rejected", 0);
        }
        var row = rows.get(0);
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("pending", ((Number) row.get("pending")).longValue());
        result.put("manual_review", ((Number) row.get("manual_review")).longValue());
        result.put("approved", ((Number) row.get("approved")).longValue());
        result.put("rejected", ((Number) row.get("rejected")).longValue());
        return result;
    }

    @Transactional
    public Map<String, Object> approvePhoto(UUID callerId, UUID photoId) {
        requireModeratorRole(callerId);
        ensurePhotoExists(photoId);
        int rows = jdbc.update(APPROVE_PHOTO_SQL, new MapSqlParameterSource()
                .addValue("photoId", photoId)
                .addValue("reviewerId", callerId));
        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "photo_not_in_reviewable_state");
        }
        writeAuditLog(callerId, "PHOTO_APPROVED", "profile_photos", photoId,
                "{\"moderation_status\": \"APPROVED\"}");
        return Map.of("photoId", photoId, "moderationStatus", "APPROVED");
    }

    @Transactional
    public Map<String, Object> rejectPhoto(UUID callerId, UUID photoId, String reason) {
        requireModeratorRole(callerId);
        ensurePhotoExists(photoId);
        int rows = jdbc.update(REJECT_PHOTO_SQL, new MapSqlParameterSource()
                .addValue("photoId", photoId)
                .addValue("reviewerId", callerId)
                .addValue("reason", reason));
        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "photo_not_in_reviewable_state");
        }
        writeAuditLog(callerId, "PHOTO_REJECTED", "profile_photos", photoId,
                "{\"moderation_status\": \"REJECTED\", \"reason\": \"" + escapeJson(reason) + "\"}");
        return Map.of("photoId", photoId, "moderationStatus", "REJECTED");
    }

    private void ensurePhotoExists(UUID photoId) {
        List<Map<String, Object>> rows = jdbc.queryForList(FIND_PHOTO_SQL,
                new MapSqlParameterSource().addValue("photoId", photoId));
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "photo_not_found");
        }
        if (rows.get(0).get("deleted_at") != null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "photo_not_found");
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

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private void requireModeratorRole(UUID callerId) {
        UserStatusService.UserStatus status = userStatusService.getStatus(callerId);
        if (status == null
                || (!"MODERATOR".equals(status.role()) && !"ADMIN".equals(status.role()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "access_denied");
        }
    }

    private OffsetDateTime toOffsetDateTime(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }
}
