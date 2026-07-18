package com.qaliye.backend.moderation;

import com.qaliye.backend.moderation.rekognition.ImageModerationOrchestrator;
import com.qaliye.backend.moderation.rekognition.ImageModerationStatus;
import com.qaliye.backend.moderation.rekognition.ModerationDecision;
import com.qaliye.backend.moderation.rekognition.RekognitionProviderException;
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
            SET moderation_status = :status,
                rejection_reason  = :reason,
                is_primary        = CASE WHEN :status = 'REJECTED' THEN FALSE ELSE is_primary END,
                updated_at        = NOW()
            WHERE id = :photoId AND moderation_status = 'PENDING'
            """;

    private static final String LOAD_PHOTO_STORAGE_SQL =
            "SELECT storage_bucket, storage_path, is_primary FROM profile_photos WHERE id = :photoId AND deleted_at IS NULL";

    private record StorageRef(String bucket, String path, String fullPath, boolean isPrimary) {}

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
    private final ImageModerationOrchestrator imageModerationOrchestrator;
    private final ImageModerationResultService imageModerationResultService;
    private final ModerationImageConverter moderationImageConverter;

    public PhotoModerationService(SupabaseStorageService storageService,
                                  OnboardingService onboardingService,
                                  UserStatusService userStatusService,
                                  CacheManager cacheManager,
                                  NamedParameterJdbcTemplate jdbc,
                                  ImageModerationOrchestrator imageModerationOrchestrator,
                                  ImageModerationResultService imageModerationResultService,
                                  ModerationImageConverter moderationImageConverter) {
        this.storageService = storageService;
        this.onboardingService = onboardingService;
        this.userStatusService = userStatusService;
        this.cacheManager = cacheManager;
        this.jdbc = jdbc;
        this.imageModerationOrchestrator = imageModerationOrchestrator;
        this.imageModerationResultService = imageModerationResultService;
        this.moderationImageConverter = moderationImageConverter;
    }

    private void handleInvalidModerationImage(UUID photoId, UUID userId, StorageRef ref,
                                              InvalidModerationImageException ex) {
        log.warn("Escalating photo {} to manual review due to invalid image: {}", photoId, ex.getMessage());
        escalateToManualReview(photoId, userId, ref,
                "INVALID_IMAGE_FORMAT",
                ex.getMessage() != null ? ex.getMessage() : "Unsupported image format",
                List.of("INVALID_IMAGE_FORMAT"));
    }

    private void escalateToManualReview(UUID photoId,
                                        UUID userId,
                                        StorageRef ref,
                                        String errorCode,
                                        String manualReviewReason,
                                        List<String> decisionReasons) {
        String reasonMessage = manualReviewReason != null && !manualReviewReason.isBlank()
                ? manualReviewReason
                : errorCode;

        List<String> reasons = (decisionReasons == null || decisionReasons.isEmpty())
                ? List.of(errorCode)
                : decisionReasons;

        imageModerationResultService.markError(photoId,
                errorCode,
                reasonMessage,
                reasons,
                reasonMessage);

        int updated = jdbc.update(UPDATE_PHOTO_STATUS_SQL,
                new MapSqlParameterSource()
                        .addValue("status", "MANUAL_REVIEW")
                        .addValue("reason", reasonMessage)
                        .addValue("photoId", photoId));

        if (updated > 0) {
            onboardingService.recomputeScore(userId);
            syncProfileVisibilityWithPrimary(userId);
        }
    }

    private void syncProfileVisibilityWithPrimary(UUID userId) {
        List<String> statuses = jdbc.query(
                "SELECT moderation_status FROM profile_photos WHERE user_id = :userId AND is_primary = TRUE AND deleted_at IS NULL LIMIT 1",
                Map.of("userId", userId),
                (rs, rowNum) -> rs.getString("moderation_status"));

        if (statuses == null || statuses.isEmpty()) {
            jdbc.update("UPDATE profiles SET is_visible = FALSE WHERE user_id = :userId",
                    Map.of("userId", userId));
            return;
        }

        String primaryStatus = statuses.get(0);
        boolean shouldBeVisible = "APPROVED".equals(primaryStatus) || "MANUAL_REVIEW".equals(primaryStatus);

        if (shouldBeVisible) {
            jdbc.update("UPDATE profiles SET is_visible = TRUE WHERE user_id = :userId AND is_onboarded = TRUE",
                    Map.of("userId", userId));
        } else {
            jdbc.update("UPDATE profiles SET is_visible = FALSE WHERE user_id = :userId",
                    Map.of("userId", userId));
        }
    }

    public record SyncModerationOutcome(String status, String rejectionReason) {}

    /**
     * Runs moderation synchronously and returns the outcome.  Called from the
     * registration path so the HTTP response can immediately reflect the decision.
     * On transient AWS / network failures returns {@code PENDING} so the retry
     * worker can pick it up later — no exception propagates to the caller.
     */
    public SyncModerationOutcome processPhotoModerationSync(UUID photoId, UUID userId,
                                                             String storagePath, boolean isPrimary) {
        boolean claimed = imageModerationResultService.createAndClaim(photoId, userId, storagePath);
        if (!claimed) {
            log.debug("Photo {} already claimed — returning PENDING", photoId);
            return new SyncModerationOutcome("PENDING", null);
        }
        StorageRef ref = null;
        try {
            ref = resolveStorageRef(photoId, storagePath, isPrimary);
            if (ref == null) {
                imageModerationResultService.markError(photoId, "PHOTO_NOT_FOUND",
                        "Photo record not found in database",
                        List.of("PHOTO_NOT_FOUND"),
                        "Photo record not found in database");
                return new SyncModerationOutcome("PENDING", null);
            }

            byte[] originalBytes = storageService.downloadPhoto(ref.fullPath());
            if (originalBytes == null || originalBytes.length == 0) {
                String reason = "Image could not be downloaded from Supabase Storage";
                escalateToManualReview(photoId, userId, ref,
                        "STORAGE_DOWNLOAD_FAILED",
                        reason,
                        List.of("STORAGE_DOWNLOAD_FAILED"));
                return new SyncModerationOutcome("MANUAL_REVIEW", reason);
            }

            byte[] moderationBytes;
            try {
                moderationBytes = moderationImageConverter.prepareForModeration(originalBytes);
            } catch (InvalidModerationImageException e) {
                handleInvalidModerationImage(photoId, userId, ref, e);
                return new SyncModerationOutcome("MANUAL_REVIEW", e.getMessage());
            }

            String imageHash = ImageModerationResultService.computeImageHash(moderationBytes);
            ModerationDecision decision = imageModerationOrchestrator.moderate(moderationBytes, ref.isPrimary());

            imageModerationResultService.markCompleted(photoId, decision, imageHash);

            String photoStatus = toPhotoStatus(decision.status());
            if (photoStatus != null) {
                int updated = jdbc.update(UPDATE_PHOTO_STATUS_SQL,
                        new MapSqlParameterSource()
                                .addValue("status", photoStatus)
                                .addValue("reason", decision.userMessage())
                                .addValue("photoId", photoId));
                if (updated > 0) {
                    if (!"REJECTED".equals(photoStatus)) {
                        onboardingService.recomputeScore(userId);
                    }
                    syncProfileVisibilityWithPrimary(userId);
                }
            }

            if (decision.status() == ImageModerationStatus.REJECTED) {
                storageService.deleteObject(ref.bucket(), ref.path());
                log.debug("Deleted rejected photo {} from storage", photoId);
            }

            return new SyncModerationOutcome(
                    photoStatus != null ? photoStatus : "PENDING",
                    decision.userMessage());

        } catch (InvalidModerationImageException e) {
            handleInvalidModerationImage(photoId, userId, ref, e);
            return new SyncModerationOutcome("MANUAL_REVIEW", e.getMessage());
        } catch (RekognitionProviderException e) {
            if ("CREDENTIALS_UNAVAILABLE".equals(e.getErrorCode())) {
                log.error("Image moderation credentials unavailable for photo {}: {}", photoId, e.getMessage());
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "IMAGE_MODERATION_CREDENTIALS_MISSING");
            }

            log.warn("Rekognition error for photo={} code={} retryable={}: {}",
                    photoId, e.getErrorCode(), e.isRetryable(), e.getMessage());
            escalateToManualReview(photoId, userId, ref,
                    e.getErrorCode(),
                    e.getMessage(),
                    List.of(e.getErrorCode()));
            return new SyncModerationOutcome("MANUAL_REVIEW", e.getMessage());
        } catch (Exception e) {
            log.error("Sync moderation failed for photoId={}: {}", photoId, e.getMessage());
            escalateToManualReview(photoId, userId, ref,
                    "INTERNAL_ERROR",
                    "Unexpected error during moderation",
                    List.of("INTERNAL_ERROR"));
            return new SyncModerationOutcome("MANUAL_REVIEW", "Unexpected error during moderation");
        }
    }

    /**
     * Runs all enabled moderation modules for a single profile photo.
     *
     * <p>The {@code storagePath} parameter accepts either the bare object key
     * ({@code userId/photoId.jpg}) or the fully-qualified path including the
     * bucket ({@code profile-photos/userId/photoId.jpg}).  When the bare key
     * is supplied the correct bucket is looked up from the database.</p>
     *
     * <p>Primary photos have face detection enforced; secondary photos are only
     * checked for nudity/sexual content.</p>
     *
     * <p>Rejected photos are deleted from Supabase Storage after the moderation
     * result is persisted.  Transient AWS or network failures leave the photo
     * stored and hidden; the retry worker will re-attempt moderation.</p>
     */
    @Async
    public void processPhotoModeration(UUID photoId, UUID userId, String storagePath,
                                       String moderationStatus, boolean isPrimary) {
        if (!"PENDING".equals(moderationStatus)) {
            return;
        }

        // Atomic claim: skip if another thread/process is already running or done
        boolean claimed = imageModerationResultService.createAndClaim(photoId, userId, storagePath);
        if (!claimed) {
            log.debug("Photo {} already claimed for moderation — skipping", photoId);
            return;
        }

        StorageRef ref = null;
        try {
            ref = resolveStorageRef(photoId, storagePath, isPrimary);
            if (ref == null) {
                log.warn("Photo {} not found in DB — cannot moderate", photoId);
                imageModerationResultService.markError(photoId, "PHOTO_NOT_FOUND",
                        "Photo record not found in database",
                        List.of("PHOTO_NOT_FOUND"),
                        "Photo record not found in database");
                return;
            }

            byte[] originalBytes = storageService.downloadPhoto(ref.fullPath());
            if (originalBytes == null || originalBytes.length == 0) {
                log.warn("Could not download photo {} from storage", photoId);
                escalateToManualReview(photoId, userId, ref,
                        "STORAGE_DOWNLOAD_FAILED",
                        "Image could not be downloaded from Supabase Storage",
                        List.of("STORAGE_DOWNLOAD_FAILED"));
                return;
            }

            byte[] moderationBytes;
            try {
                moderationBytes = moderationImageConverter.prepareForModeration(originalBytes);
            } catch (InvalidModerationImageException e) {
                handleInvalidModerationImage(photoId, userId, ref, e);
                return;
            }

            String imageHash = ImageModerationResultService.computeImageHash(moderationBytes);
            ModerationDecision decision = imageModerationOrchestrator.moderate(moderationBytes, ref.isPrimary());

            imageModerationResultService.markCompleted(photoId, decision, imageHash);

            String photoStatus = toPhotoStatus(decision.status());
            if (photoStatus != null) {
                int updated = jdbc.update(UPDATE_PHOTO_STATUS_SQL,
                        new MapSqlParameterSource()
                                .addValue("status", photoStatus)
                                .addValue("reason", decision.userMessage())
                                .addValue("photoId", photoId));
                if (updated > 0) {
                    onboardingService.recomputeScore(userId);
                    syncProfileVisibilityWithPrimary(userId);
                }
            }

            // Delete from Supabase Storage immediately after a definitive rejection.
            // Do NOT delete on ERROR/transient failures — the retry worker must be able to retry.
            if (decision.status() == ImageModerationStatus.REJECTED) {
                storageService.deleteObject(ref.bucket(), ref.path());
                log.debug("Deleted rejected photo {} from storage", photoId);
            }

        } catch (InvalidModerationImageException e) {
            handleInvalidModerationImage(photoId, userId, ref, e);
        } catch (RekognitionProviderException e) {
            log.warn("Rekognition error for photo={} code={} retryable={}: {}",
                    photoId, e.getErrorCode(), e.isRetryable(), e.getMessage());
            String manualReason = e.getMessage();
            if (manualReason == null || manualReason.isBlank()) {
                manualReason = "Rekognition provider error";
            }
            escalateToManualReview(photoId, userId, ref,
                    e.getErrorCode(),
                    manualReason,
                    List.of(e.getErrorCode()));
        } catch (Exception e) {
            log.error("Photo moderation failed for photoId={}: {}", photoId, e.getMessage());
            escalateToManualReview(photoId, userId, ref,
                    "INTERNAL_ERROR",
                    "Unexpected error during moderation",
                    List.of("INTERNAL_ERROR"));
        }
    }

    // Map orchestrator status to profile_photos.moderation_status column value.
    // Returns null when the photo status should not be changed (e.g. ERROR → stay PENDING).
    private String toPhotoStatus(ImageModerationStatus status) {
        return switch (status) {
            case APPROVED, SKIPPED -> "APPROVED";
            case REJECTED          -> "REJECTED";
            case MANUAL_REVIEW     -> "MANUAL_REVIEW";
            default                -> null;
        };
    }

    // Resolves storage coordinates from the supplied path or DB lookup.
    // isPrimaryHint is used when the path is already fully qualified (no DB lookup needed);
    // if a DB lookup happens, the stored is_primary value overrides the hint.
    private StorageRef resolveStorageRef(UUID photoId, String storagePath, boolean isPrimaryHint) {
        if (storagePath != null && storagePath.startsWith("profile-photos/")) {
            // Already bucket-qualified — skip DB lookup, use the hint
            int slash = storagePath.indexOf('/');
            String bucket = storagePath.substring(0, slash);
            String path   = storagePath.substring(slash + 1);
            return new StorageRef(bucket, path, storagePath, isPrimaryHint);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                LOAD_PHOTO_STORAGE_SQL, Map.of("photoId", photoId));
        if (rows.isEmpty()) return null;
        String bucket    = (String)  rows.get(0).get("storage_bucket");
        String path      = (String)  rows.get(0).get("storage_path");
        boolean isPrimary = Boolean.TRUE.equals(rows.get(0).get("is_primary"));
        return new StorageRef(bucket, path, bucket + "/" + path, isPrimary);
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
            syncProfileVisibilityWithPrimary(userIds.get(0));
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
