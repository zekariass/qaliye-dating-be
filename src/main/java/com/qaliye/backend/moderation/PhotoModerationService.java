package com.qaliye.backend.moderation;

import com.qaliye.backend.onboarding.OnboardingService;
import com.qaliye.backend.storage.SupabaseStorageService;
import com.qaliye.backend.user.UserStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Async;
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
public class PhotoModerationService {

    private static final Logger log = LoggerFactory.getLogger(PhotoModerationService.class);

    private static final String UPDATE_PHOTO_STATUS_SQL = """
            UPDATE profile_photos
            SET moderation_status = :result
            WHERE id = :photoId AND moderation_status = 'PENDING'
            """;

    private static final String GET_PHOTO_QUEUE_SQL = """
            SELECT pp.id, pp.user_id, pp.storage_bucket, pp.storage_path, pp.moderation_status,
                   pp.created_at, p.display_name
            FROM profile_photos pp
            JOIN profiles p ON p.user_id = pp.user_id
            WHERE pp.moderation_status = :status
              AND pp.deleted_at IS NULL
            ORDER BY pp.created_at ASC
            LIMIT 100
            """;

    private static final String REVIEW_PHOTO_SQL = """
            UPDATE profile_photos SET moderation_status = :status WHERE id = :photoId
            RETURNING user_id
            """;

    private static final String GET_REPORT_QUEUE_SQL = """
            SELECT ur.id, ur.reporter_user_id, ur.reported_user_id, ur.report_type,
                   ur.description, ur.related_message_id, ur.status, ur.created_at,
                   p.display_name AS reported_display_name
            FROM user_reports ur
            JOIN profiles p ON p.user_id = ur.reported_user_id
            WHERE ur.status = :status
            ORDER BY ur.created_at ASC
            LIMIT 100
            """;

    private static final String RESOLVE_REPORT_SQL = """
            UPDATE user_reports
            SET status = :resolution, reviewed_by = :moderatorId, reviewed_at = NOW()
            WHERE id = :reportId
            RETURNING reported_user_id
            """;

    private static final String SUSPEND_USER_SQL =
            "UPDATE app_users SET status = 'SUSPENDED' WHERE id = :userId";

    private static final String AUDIT_LOG_SQL = """
            INSERT INTO audit_log (actor_user_id, action, target_table, target_id, details)
            VALUES (:actorId, :action, :targetTable, :targetId, :details::jsonb)
            """;

    private final SupabaseStorageService storageService;
    private final OnboardingService onboardingService;
    private final UserStatusService userStatusService;
    private final CacheManager cacheManager;
    private final NamedParameterJdbcTemplate jdbc;

    public PhotoModerationService(SupabaseStorageService storageService,
                                  OnboardingService onboardingService,
                                  UserStatusService userStatusService,
                                  CacheManager cacheManager,
                                  NamedParameterJdbcTemplate jdbc) {
        this.storageService = storageService;
        this.onboardingService = onboardingService;
        this.userStatusService = userStatusService;
        this.cacheManager = cacheManager;
        this.jdbc = jdbc;
    }

    @Async
    public void processPhotoModeration(UUID photoId, UUID userId, String storagePath,
                                       String moderationStatus) {
        try {
            if (!"PENDING".equals(moderationStatus)) {
                return;
            }

            byte[] imageBytes = storageService.downloadPhoto(storagePath);
            if (imageBytes == null) {
                log.warn("Could not download photo {} for moderation", photoId);
                return;
            }

            ModerationResult result = moderateImage(imageBytes);

            int updated = jdbc.update(UPDATE_PHOTO_STATUS_SQL,
                    Map.of("result", result.name(), "photoId", photoId));

            if (updated > 0) {
                onboardingService.recomputeScore(userId);
            }
        } catch (Exception e) {
            log.error("Photo moderation failed for photoId={}: {}", photoId, e.getMessage());
        }
    }

    public ModerationResult moderateImage(byte[] imageBytes) {
        // TODO: Integrate content-safety vision API (e.g., AWS Rekognition, Azure Content Moderator)
        return ModerationResult.APPROVED; // Placeholder for MVP
    }

    public List<PhotoModerationItemDto> getPhotoQueue(UUID moderatorId, String status) {
        requireModeratorRole(moderatorId);
        List<PhotoModerationItemDto> items = new ArrayList<>();
        jdbc.query(GET_PHOTO_QUEUE_SQL, Map.of("status", status), rs -> {
            items.add(new PhotoModerationItemDto(
                    rs.getObject("id", UUID.class),
                    rs.getObject("user_id", UUID.class),
                    storageService.generateSignedUrl(rs.getString("storage_bucket"), rs.getString("storage_path"), 3600),
                    rs.getString("moderation_status"),
                    toOffsetDateTime(rs.getTimestamp("created_at")),
                    rs.getString("display_name")
            ));
        });
        return items;
    }

    @Transactional
    public void reviewPhoto(UUID moderatorId, UUID photoId, String status) {
        requireModeratorRole(moderatorId);

        List<UUID> userIds = jdbc.query(REVIEW_PHOTO_SQL,
                Map.of("status", status, "photoId", photoId),
                (rs, rowNum) -> rs.getObject("user_id", UUID.class));

        if (!userIds.isEmpty()) {
            onboardingService.recomputeScore(userIds.get(0));
        }

        String details = "{\"status\": \"" + status + "\"}";
        writeAuditLog(moderatorId, "PHOTO_MODERATION_REVIEWED", "profile_photos", photoId, details);
    }

    public List<ReportItemDto> getReportQueue(UUID moderatorId, String status) {
        requireModeratorRole(moderatorId);
        List<ReportItemDto> items = new ArrayList<>();
        jdbc.query(GET_REPORT_QUEUE_SQL, Map.of("status", status), rs -> {
            items.add(new ReportItemDto(
                    rs.getObject("id", UUID.class),
                    rs.getObject("reporter_user_id", UUID.class),
                    rs.getObject("reported_user_id", UUID.class),
                    rs.getString("report_type"),
                    rs.getString("description"),
                    rs.getObject("related_message_id", UUID.class),
                    rs.getString("status"),
                    toOffsetDateTime(rs.getTimestamp("created_at")),
                    rs.getString("reported_display_name")
            ));
        });
        return items;
    }

    @Transactional
    public void resolveReport(UUID moderatorId, UUID reportId,
                              String resolution, String banReason) {
        requireModeratorRole(moderatorId);

        List<UUID> reportedUserIds = jdbc.query(RESOLVE_REPORT_SQL,
                new MapSqlParameterSource()
                        .addValue("resolution", resolution)
                        .addValue("moderatorId", moderatorId)
                        .addValue("reportId", reportId),
                (rs, rowNum) -> rs.getObject("reported_user_id", UUID.class));

        if ("RESOLVED_BANNED".equals(resolution) && !reportedUserIds.isEmpty()) {
            UUID reportedUserId = reportedUserIds.get(0);
            jdbc.update(SUSPEND_USER_SQL, Map.of("userId", reportedUserId));
            Cache cache = cacheManager.getCache("userStatus");
            if (cache != null) {
                cache.evict(reportedUserId);
            }
        }

        String details = "{\"resolution\": \"" + resolution + "\""
                + (banReason != null && !banReason.isBlank()
                   ? ", \"ban_reason\": \"" + banReason + "\"" : "")
                + "}";
        writeAuditLog(moderatorId, "REPORT_RESOLVED", "user_reports", reportId, details);
    }

    private void requireModeratorRole(UUID callerId) {
        UserStatusService.UserStatus status = userStatusService.getStatus(callerId);
        if (status == null
                || (!"MODERATOR".equals(status.role()) && !"ADMIN".equals(status.role()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "access_denied");
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

    private OffsetDateTime toOffsetDateTime(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }
}
