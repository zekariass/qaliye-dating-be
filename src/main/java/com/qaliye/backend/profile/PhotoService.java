package com.qaliye.backend.profile;

import com.qaliye.backend.onboarding.OnboardingService;
import com.qaliye.backend.storage.SupabaseStorageService;
import com.qaliye.backend.user.repository.ProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PhotoService {

    private static final Logger log = LoggerFactory.getLogger(PhotoService.class);
    private static final long MAX_FILE_SIZE = 50L * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final NamedParameterJdbcTemplate jdbc;
    private final SupabaseStorageService storageService;
    private final ProfileRepository profileRepository;
    private final OnboardingService onboardingService;

    public PhotoService(NamedParameterJdbcTemplate jdbc,
                        SupabaseStorageService storageService,
                        ProfileRepository profileRepository,
                        OnboardingService onboardingService) {
        this.jdbc = jdbc;
        this.storageService = storageService;
        this.profileRepository = profileRepository;
        this.onboardingService = onboardingService;
    }

    @Transactional
    public Map<String, Object> uploadPhoto(UUID userId, MultipartFile file, int photoOrder, boolean isPrimary)
            throws IOException {
        if (profileRepository.findByUserId(userId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PROFILE_REQUIRED");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PHOTO_UPLOAD_FAILED");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PHOTO_UPLOAD_FAILED");
        }
        if (photoOrder < 0 || photoOrder > 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PHOTO_UPLOAD_FAILED");
        }

        UUID photoId = UUID.randomUUID();
        String ext = contentType.equals("image/png") ? "png" : contentType.equals("image/webp") ? "webp" : "jpg";
        String storagePath = userId + "/" + photoId + "." + ext;

        storageService.uploadFile("profile-photos", storagePath, file.getBytes(), contentType);

        try {
            if (isPrimary) {
                jdbc.update("UPDATE profile_photos SET is_primary = FALSE WHERE user_id = :userId",
                        Map.of("userId", userId));
            }

            jdbc.update("""
                    INSERT INTO profile_photos
                        (id, user_id, storage_bucket, storage_path, photo_order, is_primary, moderation_status)
                    VALUES (:id, :userId, 'profile-photos', :storagePath, :photoOrder, :isPrimary, 'PENDING')
                    """,
                    Map.of("id", photoId,
                           "userId", userId,
                           "storagePath", storagePath,
                           "photoOrder", photoOrder,
                           "isPrimary", isPrimary));
        } catch (Exception e) {
            log.error("Photo DB insert failed for user={}, path={}: {}", userId, storagePath, e.getMessage(), e);
            storageService.deleteObject("profile-photos", storagePath);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "PHOTO_UPLOAD_FAILED");
        }

        String signedUrl = storageService.generateSignedUrl("profile-photos", storagePath, 3600);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", photoId.toString());
        result.put("photo_order", photoOrder);
        result.put("is_primary", isPrimary);
        result.put("moderation_status", "PENDING");
        result.put("signed_url", signedUrl);
        return result;
    }

    public List<Map<String, Object>> getMyPhotos(UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT id, photo_order, is_primary, moderation_status, rejection_reason, storage_path
                FROM profile_photos
                WHERE user_id = :userId
                ORDER BY photo_order ASC
                """,
                Map.of("userId", userId));

        return rows.stream().map(row -> {
            Map<String, Object> photo = new LinkedHashMap<>();
            photo.put("id", row.get("id").toString());
            photo.put("photo_order", row.get("photo_order"));
            photo.put("is_primary", row.get("is_primary"));
            photo.put("moderation_status", row.get("moderation_status"));
            photo.put("rejection_reason", row.get("rejection_reason"));
            String storagePath = (String) row.get("storage_path");
            photo.put("signed_url", storageService.generateSignedUrl("profile-photos", storagePath, 3600));
            return photo;
        }).toList();
    }

    @Transactional
    public Map<String, Object> replacePrimaryPhoto(UUID userId, MultipartFile file) throws IOException {
        if (profileRepository.findByUserId(userId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PROFILE_REQUIRED");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PHOTO_UPLOAD_FAILED");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PHOTO_UPLOAD_FAILED");
        }

        UUID photoId = UUID.randomUUID();
        String ext = "image/png".equals(contentType) ? "png" : "image/webp".equals(contentType) ? "webp" : "jpg";
        String storagePath = userId + "/" + photoId + "." + ext;

        storageService.uploadFile("profile-photos", storagePath, file.getBytes(), contentType);

        try {
            List<Map<String, Object>> oldPrimary = jdbc.queryForList(
                    "SELECT storage_bucket, storage_path FROM profile_photos WHERE user_id = :userId AND is_primary = TRUE",
                    Map.of("userId", userId));

            jdbc.update("UPDATE profile_photos SET is_primary = FALSE WHERE user_id = :userId",
                    Map.of("userId", userId));

            jdbc.update("""
                    INSERT INTO profile_photos
                        (id, user_id, storage_bucket, storage_path, photo_order, is_primary, moderation_status)
                    VALUES (:id, :userId, 'profile-photos', :storagePath, 0, TRUE, 'PENDING')
                    """,
                    Map.of("id", photoId, "userId", userId, "storagePath", storagePath));

            if (!oldPrimary.isEmpty()) {
                try {
                    storageService.deleteObject(
                            (String) oldPrimary.get(0).get("storage_bucket"),
                            (String) oldPrimary.get(0).get("storage_path"));
                } catch (Exception ex) {
                    log.warn("Could not delete old primary photo from storage: {}", ex.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Primary photo DB insert failed for user={}, path={}: {}", userId, storagePath, e.getMessage(), e);
            storageService.deleteObject("profile-photos", storagePath);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "PHOTO_UPLOAD_FAILED");
        }

        onboardingService.recomputeScore(userId);
        String signedUrl = storageService.generateSignedUrl("profile-photos", storagePath, 3600);

        Map<String, Object> photo = new LinkedHashMap<>();
        photo.put("id", photoId.toString());
        photo.put("photo_order", 0);
        photo.put("is_primary", true);
        photo.put("moderation_status", "PENDING");
        photo.put("signed_url", signedUrl);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("photo", photo);
        result.put("onboarding", onboardingStatusToMap(onboardingService.getStatus(userId)));
        return result;
    }

    @Transactional
    public Map<String, Object> patchPhoto(UUID userId, UUID photoId, PatchPhotoRequest request) {
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id, photo_order, is_primary, moderation_status, rejection_reason, storage_path FROM profile_photos WHERE id = :photoId AND user_id = :userId",
                Map.of("photoId", photoId, "userId", userId));
        if (existing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PHOTO_NOT_FOUND");
        }

        if (request.photoOrder() != null) {
            jdbc.update("UPDATE profile_photos SET photo_order = :order, updated_at = NOW() WHERE id = :id AND user_id = :userId",
                    Map.of("order", request.photoOrder(), "id", photoId, "userId", userId));
        }

        if (Boolean.TRUE.equals(request.isPrimary())) {
            jdbc.update("UPDATE profile_photos SET is_primary = FALSE WHERE user_id = :userId",
                    Map.of("userId", userId));
            jdbc.update("UPDATE profile_photos SET is_primary = TRUE, updated_at = NOW() WHERE id = :id AND user_id = :userId",
                    Map.of("id", photoId, "userId", userId));
        } else if (Boolean.FALSE.equals(request.isPrimary())) {
            jdbc.update("UPDATE profile_photos SET is_primary = FALSE, updated_at = NOW() WHERE id = :id AND user_id = :userId",
                    Map.of("id", photoId, "userId", userId));
        }

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id, photo_order, is_primary, moderation_status, rejection_reason, storage_path FROM profile_photos WHERE id = :id",
                Map.of("id", photoId));

        Map<String, Object> photo = new LinkedHashMap<>();
        photo.put("id", row.get("id").toString());
        photo.put("photo_order", row.get("photo_order"));
        photo.put("is_primary", row.get("is_primary"));
        photo.put("moderation_status", row.get("moderation_status"));
        photo.put("rejection_reason", row.get("rejection_reason"));
        photo.put("signed_url", storageService.generateSignedUrl("profile-photos", (String) row.get("storage_path"), 3600));
        return photo;
    }

    @Transactional
    public Map<String, Object> deletePhoto(UUID userId, UUID photoId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT storage_bucket, storage_path FROM profile_photos WHERE id = :photoId AND user_id = :userId",
                Map.of("photoId", photoId, "userId", userId));
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PHOTO_NOT_FOUND");
        }

        String storageBucket = (String) rows.get(0).get("storage_bucket");
        String storagePath = (String) rows.get(0).get("storage_path");

        jdbc.update("DELETE FROM profile_photos WHERE id = :photoId AND user_id = :userId",
                Map.of("photoId", photoId, "userId", userId));

        try {
            storageService.deleteObject(storageBucket, storagePath);
        } catch (Exception ex) {
            log.warn("Could not delete photo from storage: {}", ex.getMessage());
        }

        onboardingService.recomputeScore(userId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("onboarding", onboardingStatusToMap(onboardingService.getStatus(userId)));
        return result;
    }

    private Map<String, Object> onboardingStatusToMap(OnboardingService.OnboardingStatus s) {
        Map<String, Object> onboarding = new LinkedHashMap<>();
        onboarding.put("is_onboarded", s.isOnboarded());
        onboarding.put("next_step", s.nextStep());
        onboarding.put("can_complete_onboarding", s.canCompleteOnboarding());
        onboarding.put("can_enter_discovery", s.canEnterDiscovery());
        onboarding.put("blocking_reasons", s.blockingReasons());
        return onboarding;
    }
}
