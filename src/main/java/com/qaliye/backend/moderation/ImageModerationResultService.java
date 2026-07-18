package com.qaliye.backend.moderation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaliye.backend.moderation.rekognition.*;
import com.qaliye.backend.notifications.worker.BackoffCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * JDBC-based persistence for {@code image_moderation_results}.
 * <p>
 * Uses an upsert + atomic claim pattern to prevent duplicate concurrent
 * moderation runs for the same photo:
 * <ol>
 *   <li>INSERT … ON CONFLICT DO NOTHING to ensure the row exists.</li>
 *   <li>UPDATE … SET status = 'PROCESSING' WHERE status IN ('PENDING','ERROR')
 *       AND retry_after IS NULL OR retry_after &lt;= NOW()
 *       — returns number of rows updated.</li>
 *   <li>If 0 rows updated the photo is already being processed or is done.</li>
 * </ol>
 */
@Service
public class ImageModerationResultService {

    private static final Logger log = LoggerFactory.getLogger(ImageModerationResultService.class);

    private static final String INSERT_PENDING_SQL = """
            INSERT INTO image_moderation_results
                (id, image_id, profile_id, provider, status, image_storage_path, config_version,
                 face_detection_enabled, nudity_moderation_enabled, created_at, updated_at)
            VALUES (:id, :imageId, :profileId, 'REKOGNITION', 'PENDING', :storagePath, :configVersion,
                    :faceEnabled, :nudityEnabled, NOW(), NOW())
            ON CONFLICT (image_id) DO NOTHING
            """;

    private static final String CLAIM_FOR_PROCESSING_SQL = """
            UPDATE image_moderation_results
            SET status = 'PROCESSING', updated_at = NOW()
            WHERE image_id = :imageId
              AND status IN ('PENDING', 'ERROR')
              AND (retry_after IS NULL OR retry_after <= NOW())
            """;

    private static final String UPDATE_COMPLETED_SQL = """
            UPDATE image_moderation_results
            SET status                   = :status,
                face_detection_enabled   = :faceEnabled,
                nudity_moderation_enabled = :nudityEnabled,
                face_count               = :faceCount,
                selected_face_confidence = :faceConf,
                brightness               = :brightness,
                sharpness                = :sharpness,
                face_area_percentage     = :faceArea,
                face_occluded            = :faceOccluded,
                nudity_detected          = :nudityDetected,
                sexual_content_detected  = :sexualDetected,
                moderation_labels        = :labels::jsonb,
                decision_reasons         = :reasons::TEXT[],
                manual_review_reason     = :reviewReason,
                image_hash               = :imageHash,
                config_version           = :configVersion,
                attempt_count            = attempt_count + 1,
                processed_at             = NOW(),
                updated_at               = NOW()
            WHERE image_id = :imageId
            """;

    private static final String UPDATE_ERROR_SQL = """
            UPDATE image_moderation_results
            SET status              = 'ERROR',
                last_error_code     = :errorCode,
                last_error_message  = :errorMessage,
                decision_reasons    = :reasons::TEXT[],
                manual_review_reason = :manualReason,
                attempt_count       = attempt_count + 1,
                retry_after         = :retryAfter,
                updated_at          = NOW()
            WHERE image_id = :imageId
            """;

    private static final String FIND_BY_IMAGE_ID_SQL = """
            SELECT id, image_id, profile_id, status, attempt_count, config_version, retry_after
            FROM image_moderation_results
            WHERE image_id = :imageId
            """;

    private static final String FIND_RETRY_ELIGIBLE_SQL = """
            SELECT imr.image_id, imr.profile_id, imr.image_storage_path, imr.attempt_count,
                   pp.is_primary
            FROM image_moderation_results imr
            JOIN profile_photos pp ON pp.id = imr.image_id
            WHERE imr.status = 'ERROR'
              AND (imr.retry_after IS NULL OR imr.retry_after <= NOW())
            ORDER BY imr.updated_at ASC
            LIMIT :limit
            """;

    private static final String FIND_STALLED_PENDING_SQL = """
            SELECT pp.id AS image_id, pp.user_id AS profile_id,
                   pp.storage_bucket || '/' || pp.storage_path AS full_storage_path,
                   pp.is_primary
            FROM profile_photos pp
            WHERE pp.moderation_status = 'PENDING'
              AND pp.deleted_at IS NULL
              AND pp.created_at < NOW() - INTERVAL '5 minutes'
              AND NOT EXISTS (
                  SELECT 1 FROM image_moderation_results imr
                  WHERE imr.image_id = pp.id
                    AND imr.status NOT IN ('ERROR')
              )
            ORDER BY pp.created_at ASC
            LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ImageModerationProperties props;

    public ImageModerationResultService(NamedParameterJdbcTemplate jdbc,
                                        ObjectMapper objectMapper,
                                        ImageModerationProperties props) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    /**
     * Ensures a row exists and atomically claims it for processing.
     *
     * @return {@code true} if this caller successfully claimed the row and
     *         should proceed with moderation; {@code false} if another process
     *         is already running or the photo is already in a terminal state.
     */
    @Transactional
    public boolean createAndClaim(UUID imageId, UUID profileId, String storagePath) {
        String configVersion = props.configVersion();

        // Insert if absent (idempotent)
        jdbc.update(INSERT_PENDING_SQL, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("imageId", imageId)
                .addValue("profileId", profileId)
                .addValue("storagePath", storagePath)
                .addValue("configVersion", configVersion)
                .addValue("faceEnabled", props.getFaceDetection().isEnabled())
                .addValue("nudityEnabled", props.getNudityModeration().isEnabled()));

        // Atomic claim
        int updated = jdbc.update(CLAIM_FOR_PROCESSING_SQL,
                Map.of("imageId", imageId));
        return updated > 0;
    }

    /**
     * Persists a completed (terminal) decision.
     */
    public void markCompleted(UUID imageId, ModerationDecision decision, String imageHash) {
        FaceAnalysisResult   face   = decision.faceResult();
        NudityAnalysisResult nudity = decision.nudityResult();

        String labelsJson   = buildLabelsJson(face, nudity);
        String reasonsArray = toPostgresArray(decision.reasons());
        String reviewReason = decision.status() == ImageModerationStatus.MANUAL_REVIEW
                ? decision.userMessage() : null;

        jdbc.update(UPDATE_COMPLETED_SQL, new MapSqlParameterSource()
                .addValue("status", decision.status().name())
                .addValue("faceEnabled", decision.faceDetectionRan())
                .addValue("nudityEnabled", decision.nudityModerationRan())
                .addValue("faceCount",   face != null ? face.faceCount()               : null)
                .addValue("faceConf",    face != null ? face.selectedFaceConfidence()  : null)
                .addValue("brightness",  face != null ? face.brightness()              : null)
                .addValue("sharpness",   face != null ? face.sharpness()               : null)
                .addValue("faceArea",    face != null ? face.faceAreaPercentage()       : null)
                .addValue("faceOccluded",face != null ? face.faceOccluded()            : null)
                .addValue("nudityDetected",  nudity != null && nudity.nudityDetected())
                .addValue("sexualDetected",  nudity != null && nudity.sexualContentDetected())
                .addValue("labels",      labelsJson)
                .addValue("reasons",     reasonsArray)
                .addValue("reviewReason", reviewReason)
                .addValue("imageHash",   imageHash)
                .addValue("configVersion", props.configVersion())
                .addValue("imageId",     imageId));
    }

    /**
     * Marks a moderation attempt as failed and schedules the next retry using
     * exponential backoff.
     */
    public void markError(UUID imageId,
                          String errorCode,
                          String errorMessage,
                          List<String> decisionReasons,
                          String manualReviewReason) {
        Optional<Map<String, Object>> existing = findByImageId(imageId);
        int attempts = existing
                .map(r -> ((Number) r.get("attempt_count")).intValue())
                .orElse(0);

        long backoffSeconds = BackoffCalculator.compute(attempts + 1,
                props.getMaxRetries() * 60L);
        OffsetDateTime retryAfter = OffsetDateTime.now(ZoneOffset.UTC)
                .plusSeconds(backoffSeconds);

        jdbc.update(UPDATE_ERROR_SQL, new MapSqlParameterSource()
                .addValue("errorCode", truncate(errorCode, 100))
                .addValue("errorMessage", truncate(errorMessage, 500))
                .addValue("reasons", toPostgresArray(decisionReasons))
                .addValue("manualReason", truncate(manualReviewReason, 500))
                .addValue("retryAfter", retryAfter)
                .addValue("imageId", imageId));
    }

    /**
     * Returns the current result row for an image, if any.
     */
    public Optional<Map<String, Object>> findByImageId(UUID imageId) {
        List<Map<String, Object>> rows = jdbc.queryForList(FIND_BY_IMAGE_ID_SQL,
                Map.of("imageId", imageId));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Returns ERROR rows eligible for retry (attempt_count &lt; maxRetries
     * is enforced at the caller level to avoid infinite loops).
     */
    public List<Map<String, Object>> findRetryEligible(int limit) {
        return jdbc.queryForList(FIND_RETRY_ELIGIBLE_SQL, Map.of("limit", limit));
    }

    /**
     * Returns PENDING profile photos that have no moderation result or only an
     * ERROR result, and were created more than 5 minutes ago (giving the
     * Supabase webhook enough time to fire first).
     */
    public List<Map<String, Object>> findStalledPending(int limit) {
        return jdbc.queryForList(FIND_STALLED_PENDING_SQL, Map.of("limit", limit));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Computes a SHA-256 hex digest of the image bytes for duplicate-processing
     * prevention.  Returns null on unexpected error.
     */
    public static String computeImageHash(byte[] imageBytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(imageBytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    private String buildLabelsJson(FaceAnalysisResult face, NudityAnalysisResult nudity) {
        Map<String, Object> labels = new java.util.LinkedHashMap<>();
        if (face != null && !face.failureReasons().isEmpty()) {
            labels.put("faceFailures", face.failureReasons());
        }
        if (nudity != null && !nudity.triggeredLabels().isEmpty()) {
            labels.put("nudityLabels", nudity.triggeredLabels());
        }
        try {
            return objectMapper.writeValueAsString(labels);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private String toPostgresArray(List<String> items) {
        if (items == null || items.isEmpty()) return "{}";
        return "{" + String.join(",", items.stream()
                .map(s -> "\"" + s.replace("\"", "\\\"") + "\"")
                .toList()) + "}";
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
