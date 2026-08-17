package com.qaliye.backend.verification;

import com.qaliye.backend.moderation.ModerationImageConverter;
import com.qaliye.backend.moderation.InvalidModerationImageException;
import com.qaliye.backend.moderation.rekognition.RekognitionImageClient;
import com.qaliye.backend.moderation.rekognition.RekognitionProviderException;
import com.qaliye.backend.storage.SupabaseStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class IdentityVerificationService {

    private static final Logger log = LoggerFactory.getLogger(IdentityVerificationService.class);

    private static final String PROFILE_PHOTO_SQL = """
            SELECT storage_path
            FROM profile_photos
            WHERE user_id = :userId
              AND is_primary = TRUE
              AND moderation_status = 'APPROVED'
              AND deleted_at IS NULL
            LIMIT 1
            """;

    private static final String GET_VERIFICATION_STATUS_SQL = """
            SELECT verification_status
            FROM app_users
            WHERE id = :userId
            """;

    private static final String SET_PENDING_SQL = """
            UPDATE app_users
            SET verification_status = 'PENDING'
            WHERE id = :userId
              AND verification_status IN ('NOT_STARTED', 'FAILED', 'MANUAL_REVIEW')
            """;

    private static final String SET_VERIFIED_SQL = """
            UPDATE app_users
            SET verification_status          = 'VERIFIED',
                verification_result_message  = :message,
                verified_at                  = NOW()
            WHERE id = :userId
            """;

    private static final String SET_PROFILE_VERIFIED_SQL = """
            UPDATE profiles SET is_verified = TRUE WHERE user_id = :userId
            """;

    private static final String SET_PROFILE_UNVERIFIED_SQL = """
            UPDATE profiles SET is_verified = FALSE WHERE user_id = :userId
            """;

    private static final String SET_FAILED_SQL = """
            UPDATE app_users
            SET verification_status         = 'FAILED',
                verification_result_message = :message
            WHERE id = :userId
            """;

    private static final String SET_MANUAL_REVIEW_SQL = """
            UPDATE app_users
            SET verification_status         = 'MANUAL_REVIEW',
                verification_result_message = :message
            WHERE id = :userId
            """;

    private static final String INSERT_REVIEW_SQL = """
            INSERT INTO identity_verification_reviews (user_id, selfie_path)
            VALUES (:userId, :selfiePath)
            RETURNING id
            """;

    private static final String FIND_PENDING_REVIEW_SQL = """
            SELECT id, selfie_path
            FROM identity_verification_reviews
            WHERE user_id = :userId AND status = 'PENDING'
            ORDER BY created_at DESC
            LIMIT 1
            """;

    private static final String FIND_LATEST_REVIEW_SQL = """
            SELECT id, selfie_path, status, reviewer_note, created_at, reviewed_at
            FROM identity_verification_reviews
            WHERE user_id = :userId
            ORDER BY created_at DESC
            LIMIT 1
            """;

    private static final String UPDATE_REVIEW_SELFIE_SQL = """
            UPDATE identity_verification_reviews
            SET selfie_path = :selfiePath,
                created_at = NOW()
            WHERE id = :reviewId
            """;

    private static final String PENDING_REVIEWS_SQL = """
            SELECT ivr.id, ivr.user_id, ivr.selfie_path, ivr.created_at,
                   p.display_name, p.gender,
                   pp.storage_path AS profile_photo_path
            FROM identity_verification_reviews ivr
            JOIN app_users au ON au.id = ivr.user_id
            LEFT JOIN profiles p ON p.user_id = ivr.user_id
            LEFT JOIN profile_photos pp ON pp.user_id = ivr.user_id
                AND pp.is_primary = TRUE
                AND pp.moderation_status = 'APPROVED'
                AND pp.deleted_at IS NULL
            WHERE ivr.status = 'PENDING'
            ORDER BY ivr.created_at ASC
            LIMIT :limit OFFSET :offset
            """;

    private static final String COUNT_PENDING_SQL = """
            SELECT COUNT(*) FROM identity_verification_reviews WHERE status = 'PENDING'
            """;

    private static final String FIND_REVIEW_SQL = """
            SELECT id, user_id, selfie_path, status
            FROM identity_verification_reviews
            WHERE id = :reviewId
            """;

    private static final String APPROVE_REVIEW_SQL = """
            UPDATE identity_verification_reviews
            SET status = 'APPROVED',
                reviewer_id = :reviewerId,
                reviewer_note = :note,
                reviewed_at = NOW()
            WHERE id = :reviewId AND status = 'PENDING'
            """;

    private static final String REJECT_REVIEW_SQL = """
            UPDATE identity_verification_reviews
            SET status = 'REJECTED',
                reviewer_id = :reviewerId,
                reviewer_note = :note,
                reviewed_at = NOW()
            WHERE id = :reviewId AND status = 'PENDING'
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final RekognitionImageClient rekognitionClient;
    private final SupabaseStorageService storageService;
    private final ModerationImageConverter imageConverter;
    private final IdentityVerificationProperties verificationProperties;

    public IdentityVerificationService(NamedParameterJdbcTemplate jdbc,
                                        RekognitionImageClient rekognitionClient,
                                        SupabaseStorageService storageService,
                                        ModerationImageConverter imageConverter,
                                        IdentityVerificationProperties verificationProperties) {
        this.jdbc = jdbc;
        this.rekognitionClient = rekognitionClient;
        this.storageService = storageService;
        this.imageConverter = imageConverter;
        this.verificationProperties = verificationProperties;
    }

    public record IdentityVerificationResponse(String verificationStatus, String errorCode, String resultMessage) {}

    private static IdentityVerificationResponse failed(String errorCode, String message) {
        return new IdentityVerificationResponse("FAILED", errorCode, message);
    }

    @Transactional
    public IdentityVerificationResponse verify(UUID userId, byte[] selfieBytes) {
        // 1. Check current status — idempotent if already VERIFIED
        String currentStatus = getVerificationStatus(userId);
        if ("VERIFIED".equals(currentStatus)) {
            return new IdentityVerificationResponse("VERIFIED", null, "Identity already verified.");
        }
        if ("PENDING".equals(currentStatus)) {
            throw new IdentityVerificationException("verification_in_progress",
                    "Verification is already in progress.", HttpStatus.CONFLICT);
        }
        if ("MANUAL_REVIEW".equals(currentStatus)) {
            throw new IdentityVerificationException("manual_review_in_progress",
                    "Your identity is under manual review. Please wait for the result.", HttpStatus.CONFLICT);
        }

        // 2. Require at least one primary approved profile photo
        List<Map<String, Object>> photoRows = jdbc.queryForList(
                PROFILE_PHOTO_SQL, new MapSqlParameterSource("userId", userId));
        if (photoRows.isEmpty()) {
            throw new IdentityVerificationException("no_approved_photo",
                    "No approved profile photo found. Please upload a profile photo first.", HttpStatus.BAD_REQUEST);
        }
        String profilePhotoPath = (String) photoRows.get(0).get("storage_path");

        // 3. Mark PENDING
        int updated = jdbc.update(SET_PENDING_SQL, new MapSqlParameterSource("userId", userId));
        if (updated == 0) {
            // Status changed between check and update (concurrent request)
            throw new IdentityVerificationException("verification_in_progress",
                    "Verification is already in progress.", HttpStatus.CONFLICT);
        }

        // 4. Download profile photo bytes
        byte[] profileBytes = storageService.downloadPhoto("profile-photos/" + profilePhotoPath);
        if (profileBytes == null || profileBytes.length == 0) {
            markFailed(userId, "Could not retrieve profile photo for comparison.");
            return failed("profile_photo_unavailable",
                    "Could not retrieve profile photo for comparison.");
        }

        // 4b. Convert profile photo to JPEG if stored as WebP (Rekognition only accepts JPEG/PNG)
        try {
            profileBytes = imageConverter.prepareForRekognition(profileBytes);
        } catch (InvalidModerationImageException e) {
            String message = "Profile photo format is not supported for verification.";
            log.warn("Identity verification failed for user={}: profile photo conversion error: {}",
                    userId, e.getMessage());
            markFailed(userId, message);
            return failed("profile_photo_unsupported_format", message);
        }

        // 5. Validate selfie size (Rekognition limit: 5 MB)
        if (selfieBytes == null || selfieBytes.length == 0) {
            markFailed(userId, "Selfie image is empty.");
            return failed("selfie_empty", "Selfie image is empty.");
        }
        if (selfieBytes.length > RekognitionImageClient.MAX_IMAGE_BYTES) {
            markFailed(userId, "Selfie image exceeds the maximum allowed size of 5 MB.");
            return failed("selfie_too_large",
                    "Selfie image exceeds the maximum allowed size of 5 MB.");
        }

        // 6. Compare faces
        try {
            RekognitionImageClient.FaceComparisonResult result =
                    rekognitionClient.compareFaces(selfieBytes, profileBytes,
                            (float) verificationProperties.getSimilarityThreshold());

            if (result.matched()) {
                String message = "Identity verified successfully (similarity: "
                        + Math.round(result.similarity()) + "%).";
                jdbc.update(SET_VERIFIED_SQL, new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("message", message));
                jdbc.update(SET_PROFILE_VERIFIED_SQL, new MapSqlParameterSource("userId", userId));
                log.info("Identity verification succeeded for user={}", userId);
                return new IdentityVerificationResponse("VERIFIED", null, message);
            } else {
                String message = "Face comparison did not meet the required threshold. Please submit a clearer selfie.";
                markFailed(userId, message);
                return failed("face_not_matched", message);
            }

        } catch (RekognitionProviderException e) {
            if ("UNSUPPORTED_IMAGE_FORMAT".equals(e.getErrorCode())) {
                String message = "Unsupported image format. Please upload a JPEG or PNG image.";
                log.warn("Identity verification failed for user={}: unsupported image format", userId);
                markFailed(userId, message);
                return failed("unsupported_image_format", message);
            }
            String message = e.isRetryable()
                    ? "Verification service temporarily unavailable. Please try again."
                    : "Face comparison could not be completed. Please try again with a clearer selfie.";
            String errorCode = e.isRetryable() ? "verification_service_unavailable" : "face_comparison_failed";
            log.warn("Rekognition compareFaces failed for user={}: [{}] {}",
                    userId, e.getErrorCode(), e.getMessage());
            markFailed(userId, message);
            return failed(errorCode, message);
        }
    }

    private String getVerificationStatus(UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                GET_VERIFICATION_STATUS_SQL, new MapSqlParameterSource("userId", userId));
        if (rows.isEmpty()) {
            throw new IdentityVerificationException("user_not_found",
                    "User not found.", HttpStatus.NOT_FOUND);
        }
        Object status = rows.get(0).get("verification_status");
        return status != null ? status.toString() : "NOT_STARTED";
    }

    private void markFailed(UUID userId, String message) {
        jdbc.update(SET_FAILED_SQL, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("message", message));
        jdbc.update(SET_PROFILE_UNVERIFIED_SQL, new MapSqlParameterSource("userId", userId));
    }

    // ── Manual review (user-initiated) ──────────────────────────────────────

    public Map<String, Object> getManualReviewStatus(UUID userId) {
        String verificationStatus = getVerificationStatus(userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("verification_status", verificationStatus);

        List<Map<String, Object>> rows = jdbc.queryForList(FIND_LATEST_REVIEW_SQL,
                new MapSqlParameterSource("userId", userId));
        if (!rows.isEmpty()) {
            Map<String, Object> row = rows.get(0);
            result.put("review_id", row.get("id"));
            result.put("review_status", row.get("status"));
            result.put("submitted_at", row.get("created_at") != null ? row.get("created_at").toString() : null);
            result.put("reviewed_at", row.get("reviewed_at") != null ? row.get("reviewed_at").toString() : null);
            result.put("reviewer_note", row.get("reviewer_note"));
        }

        return result;
    }

    @Transactional
    public IdentityVerificationResponse requestManualReview(UUID userId, byte[] selfieBytes) {
        String currentStatus = getVerificationStatus(userId);
        if ("VERIFIED".equals(currentStatus)) {
            return new IdentityVerificationResponse("VERIFIED", null, "Identity already verified.");
        }
        if ("MANUAL_REVIEW".equals(currentStatus)) {
            return updateExistingManualReview(userId, selfieBytes);
        }
        if ("PENDING".equals(currentStatus)) {
            throw new IdentityVerificationException("verification_in_progress",
                    "Verification is already in progress.", HttpStatus.CONFLICT);
        }

        if (selfieBytes == null || selfieBytes.length == 0) {
            throw new IdentityVerificationException("selfie_required",
                    "A selfie image is required.", HttpStatus.BAD_REQUEST);
        }
        if (selfieBytes.length > RekognitionImageClient.MAX_IMAGE_BYTES) {
            throw new IdentityVerificationException("selfie_too_large",
                    "Selfie image exceeds the maximum allowed size of 5 MB.", HttpStatus.BAD_REQUEST);
        }

        String selfiePath = "identity-reviews/" + userId + "/" + UUID.randomUUID() + ".jpg";
        storageService.uploadFile("profile-photos", selfiePath, selfieBytes, "image/jpeg");

        jdbc.update(SET_MANUAL_REVIEW_SQL, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("message", "Manual review requested by user."));

        jdbc.queryForObject(INSERT_REVIEW_SQL, new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("selfiePath", selfiePath),
                UUID.class);

        log.info("Manual review requested for user={}", userId);
        return new IdentityVerificationResponse("MANUAL_REVIEW", null,
                "Your identity is under manual review. We will notify you when it is complete.");
    }

    private IdentityVerificationResponse updateExistingManualReview(UUID userId, byte[] selfieBytes) {
        if (selfieBytes == null || selfieBytes.length == 0) {
            throw new IdentityVerificationException("selfie_required",
                    "A selfie image is required.", HttpStatus.BAD_REQUEST);
        }
        if (selfieBytes.length > RekognitionImageClient.MAX_IMAGE_BYTES) {
            throw new IdentityVerificationException("selfie_too_large",
                    "Selfie image exceeds the maximum allowed size of 5 MB.", HttpStatus.BAD_REQUEST);
        }

        List<Map<String, Object>> rows = jdbc.queryForList(FIND_PENDING_REVIEW_SQL,
                new MapSqlParameterSource("userId", userId));
        if (rows.isEmpty()) {
            // No pending review row found (e.g. admin already processed it)
            throw new IdentityVerificationException("manual_review_already_processed",
                    "Your manual review has already been processed. Please try again.", HttpStatus.CONFLICT);
        }

        UUID reviewId = (UUID) rows.get(0).get("id");
        String oldSelfiePath = (String) rows.get(0).get("selfie_path");

        String newSelfiePath = "identity-reviews/" + userId + "/" + UUID.randomUUID() + ".jpg";
        storageService.uploadFile("profile-photos", newSelfiePath, selfieBytes, "image/jpeg");

        jdbc.update(UPDATE_REVIEW_SELFIE_SQL, new MapSqlParameterSource()
                .addValue("reviewId", reviewId)
                .addValue("selfiePath", newSelfiePath));

        // Delete old selfie image from storage (best-effort)
        if (oldSelfiePath != null) {
            storageService.deleteObject("profile-photos", oldSelfiePath);
        }

        log.info("Manual review updated for user={}, reviewId={}, old selfie deleted", userId, reviewId);
        return new IdentityVerificationResponse("MANUAL_REVIEW", null,
                "Your identity is under manual review. We will notify you when it is complete.");
    }

    // ── Admin review endpoints ──────────────────────────────────────────────

    public record ReviewQueueItem(
            UUID id,
            UUID userId,
            String displayName,
            String gender,
            String selfiePath,
            String profilePhotoPath,
            String createdAt
    ) {}

    public Map<String, Object> getPendingReviews(int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        List<Map<String, Object>> rows = jdbc.queryForList(PENDING_REVIEWS_SQL,
                new MapSqlParameterSource()
                        .addValue("limit", pageSize)
                        .addValue("offset", offset));

        List<ReviewQueueItem> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            items.add(new ReviewQueueItem(
                    (UUID) row.get("id"),
                    (UUID) row.get("user_id"),
                    (String) row.get("display_name"),
                    (String) row.get("gender"),
                    (String) row.get("selfie_path"),
                    (String) row.get("profile_photo_path"),
                    row.get("created_at").toString()
            ));
        }

        Long total = jdbc.queryForObject(COUNT_PENDING_SQL, Map.of(), Long.class);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", total != null ? total : 0);
        result.put("page", page);
        result.put("page_size", pageSize);
        return result;
    }

    @Transactional
    public Map<String, Object> approveReview(UUID adminId, UUID reviewId, String note) {
        List<Map<String, Object>> rows = jdbc.queryForList(FIND_REVIEW_SQL,
                new MapSqlParameterSource("reviewId", reviewId));
        if (rows.isEmpty()) {
            throw new IdentityVerificationException("review_not_found",
                    "Review not found.", HttpStatus.NOT_FOUND);
        }

        int updated = jdbc.update(APPROVE_REVIEW_SQL, new MapSqlParameterSource()
                .addValue("reviewId", reviewId)
                .addValue("reviewerId", adminId)
                .addValue("note", note));
        if (updated == 0) {
            throw new IdentityVerificationException("review_already_processed",
                    "This review has already been processed.", HttpStatus.CONFLICT);
        }

        UUID userId = (UUID) rows.get(0).get("user_id");
        jdbc.update(SET_VERIFIED_SQL, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("message", "Identity verified by admin review."));
        jdbc.update(SET_PROFILE_VERIFIED_SQL, new MapSqlParameterSource("userId", userId));

        log.info("Admin {} approved manual review {} for user={}", adminId, reviewId, userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("review_id", reviewId);
        result.put("status", "APPROVED");
        result.put("user_id", userId);
        return result;
    }

    @Transactional
    public Map<String, Object> rejectReview(UUID adminId, UUID reviewId, String note) {
        List<Map<String, Object>> rows = jdbc.queryForList(FIND_REVIEW_SQL,
                new MapSqlParameterSource("reviewId", reviewId));
        if (rows.isEmpty()) {
            throw new IdentityVerificationException("review_not_found",
                    "Review not found.", HttpStatus.NOT_FOUND);
        }

        int updated = jdbc.update(REJECT_REVIEW_SQL, new MapSqlParameterSource()
                .addValue("reviewId", reviewId)
                .addValue("reviewerId", adminId)
                .addValue("note", note));
        if (updated == 0) {
            throw new IdentityVerificationException("review_already_processed",
                    "This review has already been processed.", HttpStatus.CONFLICT);
        }

        UUID userId = (UUID) rows.get(0).get("user_id");
        markFailed(userId, note != null ? note : "Manual review rejected.");

        log.info("Admin {} rejected manual review {} for user={}", adminId, reviewId, userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("review_id", reviewId);
        result.put("status", "REJECTED");
        result.put("user_id", userId);
        return result;
    }
}
