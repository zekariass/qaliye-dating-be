package com.qaliye.backend.profile;

import com.qaliye.backend.moderation.PhotoModerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.qaliye.backend.profile.dto.PhotoRegistrationRequest;
import com.qaliye.backend.profile.dto.PhotoReorderItem;
import com.qaliye.backend.profile.dto.PhotoReorderRequest;
import com.qaliye.backend.profile.dto.ProfilePhotoDto;
import com.qaliye.backend.profile.dto.ProfilePhotosResponse;
import com.qaliye.backend.storage.SupabaseStorageService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProfilePhotoService {

    private static final Logger log = LoggerFactory.getLogger(ProfilePhotoService.class);

    private static final int MAX_PHOTOS = 7;
    private static final int SIGNED_URL_TTL_SECONDS = 3600;

    private final NamedParameterJdbcTemplate jdbc;
    private final SupabaseStorageService storageService;
    private final PhotoModerationService photoModerationService;

    public ProfilePhotoService(NamedParameterJdbcTemplate jdbc,
                               SupabaseStorageService storageService,
                               PhotoModerationService photoModerationService) {
        this.jdbc = jdbc;
        this.storageService = storageService;
        this.photoModerationService = photoModerationService;
    }

    public ProfilePhotosResponse getPhotos(UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, photo_order, is_primary, moderation_status, rejection_reason, storage_bucket, storage_path, created_at
                FROM profile_photos
                WHERE user_id = :userId AND deleted_at IS NULL AND moderation_status <> 'REJECTED'
                ORDER BY photo_order ASC
                """, Map.of("userId", userId));

        List<ProfilePhotoDto> photos = rows.stream()
                .map(row -> buildPhotoDto(row, SIGNED_URL_TTL_SECONDS))
                .toList();
        return new ProfilePhotosResponse(photos);
    }

    @Transactional
    public ProfilePhotoDto registerPhoto(UUID userId, PhotoRegistrationRequest request) {
        long activeCount = countActivePhotos(userId);
        if (activeCount >= MAX_PHOTOS) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "PHOTO_LIMIT_EXCEEDED");
        }

        boolean isFirst = activeCount == 0;
        boolean isPrimary = isFirst || Boolean.TRUE.equals(request.isPrimary());

        if (isPrimary) {
            jdbc.update("UPDATE profile_photos SET is_primary = FALSE WHERE user_id = :userId AND deleted_at IS NULL",
                    Map.of("userId", userId));
        }

        UUID photoId = UUID.randomUUID();
        Integer maxOrder = jdbc.queryForObject(
                "SELECT COALESCE(MAX(photo_order), -1) FROM profile_photos WHERE user_id = :userId AND deleted_at IS NULL",
                Map.of("userId", userId), Integer.class);
        int order = (maxOrder != null ? maxOrder : -1) + 1;

        jdbc.update("""
                INSERT INTO profile_photos
                    (id, user_id, storage_bucket, storage_path, photo_order, is_primary, moderation_status, metadata)
                VALUES (:id, :userId, :bucket, :path, :order, :isPrimary, 'PENDING', '{}'::JSONB)
                """,
                new MapSqlParameterSource()
                        .addValue("id", photoId)
                        .addValue("userId", userId)
                        .addValue("bucket", request.storageBucket())
                        .addValue("path", request.storagePath())
                        .addValue("order", order)
                        .addValue("isPrimary", isPrimary));

        recomputeCompletionScore(userId);

        String fullStoragePath = request.storageBucket() + "/" + request.storagePath();
        PhotoModerationService.SyncModerationOutcome outcome =
                photoModerationService.processPhotoModerationSync(photoId, userId, fullStoragePath, isPrimary);

        if ("REJECTED".equals(outcome.status())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    outcome.rejectionReason() != null ? outcome.rejectionReason()
                            : "This photo could not be approved. Please upload a different photo.");
        }

        String signedUrl = storageService.generateSignedUrl(request.storageBucket(), request.storagePath(), SIGNED_URL_TTL_SECONDS);
        return new ProfilePhotoDto(photoId, order, isPrimary, signedUrl,
                Instant.now().plusSeconds(SIGNED_URL_TTL_SECONDS), outcome.status(), null);
    }

    @Transactional
    public ProfilePhotosResponse registerPhotos(UUID userId, List<PhotoRegistrationRequest> requests) {
        long activeCount = countActivePhotos(userId);
        if (activeCount + requests.size() > MAX_PHOTOS) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "PHOTO_LIMIT_EXCEEDED");
        }
        for (PhotoRegistrationRequest request : requests) {
            registerPhoto(userId, request);
        }
        return getPhotos(userId);
    }

    @Transactional
    public ProfilePhotosResponse reorderPhotos(UUID userId, PhotoReorderRequest request) {
        List<PhotoReorderItem> items = request.photos();

        for (PhotoReorderItem item : items) {
            List<Map<String, Object>> existing = jdbc.queryForList("""
                    SELECT pp.moderation_status, imr.face_detection_enabled, imr.face_count
                    FROM profile_photos pp
                    LEFT JOIN image_moderation_results imr ON imr.image_id = pp.id
                    WHERE pp.id = :id AND pp.user_id = :userId AND pp.deleted_at IS NULL
                    """, Map.of("id", item.id(), "userId", userId));
            if (existing.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PHOTO_NOT_FOUND");
            }
            if (Boolean.TRUE.equals(item.isPrimary())) {
                String status = (String) existing.get(0).get("moderation_status");
                Boolean faceEnabled = (Boolean) existing.get(0).get("face_detection_enabled");
                Number faceCountNum = (Number) existing.get(0).get("face_count");
                int faceCount = faceCountNum != null ? faceCountNum.intValue() : 0;
                boolean faceVerified = faceEnabled == null
                        || (Boolean.TRUE.equals(faceEnabled) && faceCount > 0);
                if (!"APPROVED".equals(status) || !faceVerified) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PRIMARY_PHOTO");
                }
            }
        }

        int countPrimary = (int) items.stream().filter(i -> Boolean.TRUE.equals(i.isPrimary())).count();
        PhotoReorderItem primaryItem = items.stream()
                .filter(i -> Boolean.TRUE.equals(i.isPrimary()))
                .min((a, b) -> Integer.compare(a.photoOrder(), b.photoOrder()))
                .orElse(null);

        List<PhotoReorderItem> sorted = new ArrayList<>(items);
        sorted.sort((a, b) -> Integer.compare(a.photoOrder(), b.photoOrder()));

        // Shift existing orders out of the way first to avoid transient unique constraint
        // violations (user_id, photo_order) when two photos swap positions.
        jdbc.update("""
                UPDATE profile_photos
                SET photo_order = photo_order + :offset
                WHERE user_id = :userId AND deleted_at IS NULL
                """,
                Map.of("offset", 10000, "userId", userId));

        // Clear all primary flags so the per-row loop never leaves two rows with
        // is_primary = TRUE simultaneously (unique_active_primary_photo_per_user).
        jdbc.update("UPDATE profile_photos SET is_primary = FALSE WHERE user_id = :userId AND deleted_at IS NULL",
                Map.of("userId", userId));

        for (int i = 0; i < sorted.size(); i++) {
            PhotoReorderItem item = sorted.get(i);
            boolean assignedPrimary = countPrimary == 0
                    ? i == 0
                    : item.id().equals(primaryItem != null ? primaryItem.id() : null);

            jdbc.update("""
                    UPDATE profile_photos
                    SET photo_order = :order, is_primary = :isPrimary, updated_at = NOW()
                    WHERE id = :id AND user_id = :userId AND deleted_at IS NULL
                    """,
                    new MapSqlParameterSource()
                            .addValue("order", i)
                            .addValue("isPrimary", assignedPrimary)
                            .addValue("id", item.id())
                            .addValue("userId", userId));
        }

        promoteQualifiedPrimary(userId);
        return getPhotos(userId);
    }

    @Transactional
    public ProfilePhotosResponse deletePhoto(UUID userId, UUID photoId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, is_primary, moderation_status, storage_bucket, storage_path
                FROM profile_photos
                WHERE id = :photoId AND user_id = :userId AND deleted_at IS NULL
                """, Map.of("photoId", photoId, "userId", userId));

        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PHOTO_NOT_FOUND");
        }

        boolean wasPrimary     = Boolean.TRUE.equals(rows.get(0).get("is_primary"));
        String moderationStatus = (String) rows.get(0).get("moderation_status");
        String bucket           = (String) rows.get(0).get("storage_bucket");
        String path             = (String) rows.get(0).get("storage_path");

        if (wasPrimary) {
            Boolean isVisible = jdbc.queryForObject(
                    "SELECT is_visible FROM profiles WHERE user_id = :userId",
                    Map.of("userId", userId), Boolean.class);
            UUID candidate = findQualifiedPrimaryCandidate(userId, photoId);
            if (Boolean.TRUE.equals(isVisible) && "APPROVED".equals(moderationStatus) && candidate == null) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "NO_QUALIFIED_PRIMARY_PHOTO");
            }
        }

        jdbc.update("UPDATE profile_photos SET deleted_at = NOW() WHERE id = :photoId AND user_id = :userId",
                Map.of("photoId", photoId, "userId", userId));

        try {
            storageService.deleteObject(bucket, path);
        } catch (Exception ex) {
            log.warn("Could not delete photo {} from Supabase Storage: {}", photoId, ex.getMessage());
        }

        if (wasPrimary) {
            promoteQualifiedPrimary(userId);
        }

        recomputeCompletionScore(userId);
        return getPhotos(userId);
    }

    /**
     * Returns the id of the best candidate to become the new primary photo:
     * must be APPROVED and have passed face detection (face_detection_enabled = TRUE
     * in image_moderation_results), or have no moderation record at all (moderation
     * was globally disabled when the photo was uploaded).
     */
    private UUID findQualifiedPrimaryCandidate(UUID userId, UUID excludePhotoId) {
        List<UUID> candidates = jdbc.queryForList("""
                SELECT pp.id
                FROM profile_photos pp
                LEFT JOIN image_moderation_results imr ON imr.image_id = pp.id
                WHERE pp.user_id = :userId
                  AND pp.deleted_at IS NULL
                  AND pp.id <> :excludeId
                  AND pp.moderation_status = 'APPROVED'
                  AND (
                      imr.id IS NULL
                      OR (imr.face_detection_enabled = TRUE AND COALESCE(imr.face_count, 0) > 0)
                  )
                ORDER BY pp.photo_order ASC
                LIMIT 1
                """, Map.of("userId", userId, "excludeId", excludePhotoId), UUID.class);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private void promoteQualifiedPrimary(UUID userId) {
        jdbc.update("""
                UPDATE profile_photos
                SET is_primary = TRUE, updated_at = NOW()
                WHERE id = (
                    SELECT pp.id
                    FROM profile_photos pp
                    LEFT JOIN image_moderation_results imr ON imr.image_id = pp.id
                    WHERE pp.user_id = :userId
                      AND pp.deleted_at IS NULL
                      AND pp.moderation_status = 'APPROVED'
                      AND (
                          imr.id IS NULL
                          OR (imr.face_detection_enabled = TRUE AND COALESCE(imr.face_count, 0) > 0)
                      )
                    ORDER BY pp.photo_order ASC
                    LIMIT 1
                )
                AND NOT EXISTS (
                    SELECT 1 FROM profile_photos
                    WHERE user_id = :userId AND deleted_at IS NULL AND is_primary = TRUE
                )
                """, Map.of("userId", userId));
    }

    private long countActivePhotos(UUID userId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM profile_photos WHERE user_id = :userId AND deleted_at IS NULL AND moderation_status <> 'REJECTED'",
                Map.of("userId", userId), Long.class);
        return count != null ? count : 0L;
    }

    private long countApprovedPhotosExcluding(UUID userId, UUID excludePhotoId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM profile_photos WHERE user_id = :userId AND deleted_at IS NULL AND moderation_status = 'APPROVED' AND id != :excludeId",
                Map.of("userId", userId, "excludeId", excludePhotoId), Long.class);
        return count != null ? count : 0L;
    }

    private void recomputeCompletionScore(UUID userId) {
        jdbc.update("""
                UPDATE profiles SET profile_completion_score = :score WHERE user_id = :userId
                """, Map.of("score", computeScore(userId), "userId", userId));
    }

    int computeScore(UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT
                    p.display_name, p.gender, p.date_of_birth, p.height_cm, p.residency_type,
                    p.bio, p.ethnicity_ids, p.nationality, p.religion, p.education_level, p.occupation,
                    p.relationship_intention, p.marital_status, p.has_children, p.wants_children,
                    p.smoking_detail, p.drinking_detail, p.activity_level, p.interests, p.language_ids,
                    au.address_id
                FROM profiles p
                JOIN app_users au ON au.id = p.user_id
                WHERE p.user_id = :userId
                """, Map.of("userId", userId));

        if (rows.isEmpty()) return 0;
        Map<String, Object> r = rows.get(0);

        int filled = 0;
        if (notBlank((String) r.get("display_name")))             filled++;
        if (r.get("gender") != null)                               filled++;
        if (r.get("date_of_birth") != null)                        filled++;
        if (r.get("height_cm") != null)                            filled++;
        if (r.get("residency_type") != null)                       filled++;
        if (r.get("address_id") != null)                           filled++;

        if (notBlank((String) r.get("bio")))                       filled++;
        if (isNonEmptyArray(r.get("ethnicity_ids")))                filled++;
        if (r.get("nationality") != null)                          filled++;
        if (r.get("religion") != null)                             filled++;
        if (r.get("education_level") != null)                      filled++;
        if (r.get("occupation") != null)                           filled++;
        if (r.get("relationship_intention") != null)               filled++;
        if (r.get("marital_status") != null)                       filled++;
        if (r.get("has_children") != null)                         filled++;
        if (r.get("wants_children") != null)                       filled++;

        if (r.get("smoking_detail") != null)                       filled++;
        if (r.get("drinking_detail") != null)                      filled++;
        if (r.get("activity_level") != null)                       filled++;
        if (isNonEmptyArray(r.get("interests")))                   filled++;
        if (isNonEmptyArray(r.get("language_ids")))                filled++;

        Boolean hasPrefs = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM discovery_preferences WHERE user_id = :userId)",
                Map.of("userId", userId), Boolean.class);
        if (Boolean.TRUE.equals(hasPrefs))                         filled++;

        Boolean hasPhotos = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM profile_photos WHERE user_id = :userId AND deleted_at IS NULL)",
                Map.of("userId", userId), Boolean.class);
        if (Boolean.TRUE.equals(hasPhotos))                        filled++;

        return (int) Math.round((filled / 23.0) * 100);
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private boolean isNonEmptyArray(Object obj) {
        if (obj instanceof String[] arr) return arr.length > 0;
        if (obj instanceof java.sql.Array sqlArr) {
            try {
                Object[] arr = (Object[]) sqlArr.getArray();
                return arr != null && arr.length > 0;
            } catch (Exception ignored) {}
        }
        return false;
    }

    ProfilePhotoDto buildPhotoDto(Map<String, Object> row, int ttlSeconds) {
        UUID id = (UUID) row.get("id");
        Integer order = ((Number) row.get("photo_order")).intValue();
        Boolean isPrimary = (Boolean) row.get("is_primary");
        String status = (String) row.get("moderation_status");
        String rejectionReason = (String) row.get("rejection_reason");
        String bucket = (String) row.get("storage_bucket");
        String path = (String) row.get("storage_path");

        String signedUrl = storageService.generateSignedUrl(bucket, path, ttlSeconds);
        Instant expiresAt = Instant.now().plusSeconds(ttlSeconds);
        return new ProfilePhotoDto(id, order, isPrimary, signedUrl, expiresAt, status, rejectionReason);
    }
}
