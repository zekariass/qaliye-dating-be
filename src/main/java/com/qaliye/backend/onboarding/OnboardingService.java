package com.qaliye.backend.onboarding;

import com.qaliye.backend.user.entity.Profile;
import com.qaliye.backend.user.repository.ProfileRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class OnboardingService {

    private static final Set<String> VALID_GENDER_PREFS = Set.of("MALE", "FEMALE");

    public record OnboardingStatus(
            boolean basicProfile,
            boolean location,
            boolean photo,
            boolean preferences,
            boolean isOnboarded,
            String nextStep,
            int profileCompletionScore,
            boolean canCompleteOnboarding,
            boolean canEnterDiscovery,
            List<String> blockingReasons
    ) {}

    private final ProfileRepository profileRepository;
    private final NamedParameterJdbcTemplate jdbc;

    public OnboardingService(ProfileRepository profileRepository,
                             NamedParameterJdbcTemplate jdbc) {
        this.profileRepository = profileRepository;
        this.jdbc = jdbc;
    }

    public OnboardingStatus getStatus(UUID userId) {
        Profile profile = profileRepository.findByUserId(userId).orElse(null);

        // 1. Basic profile
        boolean basicProfile = profile != null
                && notBlank(profile.getDisplayName())
                && notBlank(profile.getGender())
                && notBlank(profile.getResidencyType())
                && notBlank(profile.getRelationshipIntention())
                && profile.getDateOfBirth() != null
                && !profile.getDateOfBirth().isAfter(LocalDate.now().minusYears(18));

        // 2. Location
        Integer locationCnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_users WHERE id = :uid AND address_id IS NOT NULL",
                Map.of("uid", userId), Integer.class);
        boolean location = locationCnt != null && locationCnt > 0;

        // 3. Primary photo
        List<String> primaryStatuses = jdbc.query(
                "SELECT moderation_status FROM profile_photos WHERE user_id = :uid AND is_primary = TRUE ORDER BY created_at DESC LIMIT 1",
                Map.of("uid", userId), (rs, row) -> rs.getString("moderation_status"));
        String primaryPhotoStatus = primaryStatuses.isEmpty() ? null : primaryStatuses.get(0);
        boolean photo = primaryPhotoStatus != null && !"REJECTED".equals(primaryPhotoStatus);

        // 4. Discovery preferences
        boolean preferences = false;
        List<Map<String, Object>> prefRows = jdbc.queryForList(
                "SELECT interested_in_gender, min_age, max_age, max_distance_km FROM discovery_preferences WHERE user_id = :uid",
                Map.of("uid", userId));
        if (!prefRows.isEmpty()) {
            Map<String, Object> pref = prefRows.get(0);
            String interestedIn = (String) pref.get("interested_in_gender");
            int minAge = ((Number) pref.get("min_age")).intValue();
            int maxAge = ((Number) pref.get("max_age")).intValue();
            int maxDist = ((Number) pref.get("max_distance_km")).intValue();
            preferences = VALID_GENDER_PREFS.contains(interestedIn)
                    && minAge >= 18 && maxAge >= minAge && maxDist > 0;
        }

        boolean canCompleteOnboarding = basicProfile && location && photo && preferences;
        boolean isOnboarded = profile != null && Boolean.TRUE.equals(profile.getIsOnboarded());

        // Next step
        String nextStep;
        if (!basicProfile)     nextStep = "BASIC_PROFILE";
        else if (!location)    nextStep = "ADD_LOCATION";
        else if (!photo)       nextStep = "ADD_PHOTO";
        else if (!preferences) nextStep = "SET_PREFERENCES";
        else if (!isOnboarded) nextStep = "COMPLETE";
        else                   nextStep = "DONE";

        // Blocking reasons
        List<String> blockingReasons = new ArrayList<>();
        if (!basicProfile)  blockingReasons.add("MISSING_BASIC_PROFILE");
        if (!location)      blockingReasons.add("MISSING_LOCATION");
        if (!photo)         blockingReasons.add("MISSING_PRIMARY_PHOTO");
        if (!preferences)   blockingReasons.add("MISSING_PREFERENCES");

        // Can enter discovery: onboarded + visible + ACTIVE user + location + APPROVED primary photo
        boolean canEnterDiscovery = false;
        if (isOnboarded && location && "APPROVED".equals(primaryPhotoStatus)) {
            boolean isVisible = Boolean.TRUE.equals(profile.getIsVisible());
            String userStatus = jdbc.queryForObject(
                    "SELECT status FROM app_users WHERE id = :uid", Map.of("uid", userId), String.class);
            canEnterDiscovery = isVisible && "ACTIVE".equals(userStatus);
        }
        if (isOnboarded && photo && !"APPROVED".equals(primaryPhotoStatus)) {
            blockingReasons.add("PRIMARY_PHOTO_PENDING_REVIEW");
        }

        int score = computeScore(profile, userId, basicProfile, location, photo, preferences);
        return new OnboardingStatus(basicProfile, location, photo, preferences, isOnboarded,
                nextStep, score, canCompleteOnboarding, canEnterDiscovery, blockingReasons);
    }

    @Transactional
    public OnboardingStatus complete(UUID callerId) {
        OnboardingStatus status = getStatus(callerId);
        if (!status.canCompleteOnboarding()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ONBOARDING_INCOMPLETE");
        }

        List<String> photoStatuses = jdbc.query(
                "SELECT moderation_status FROM profile_photos WHERE user_id = :uid AND is_primary = TRUE LIMIT 1",
                Map.of("uid", callerId), (rs, row) -> rs.getString("moderation_status"));
        String primaryPhotoStatus = photoStatuses.isEmpty() ? null : photoStatuses.get(0);

        int score = status.profileCompletionScore();
        boolean canEnterDiscovery = "APPROVED".equals(primaryPhotoStatus);
        jdbc.update(
                "UPDATE profiles SET is_onboarded = TRUE, is_visible = :isVisible, profile_completion_score = :score WHERE user_id = :userId",
                Map.of("isVisible", canEnterDiscovery, "score", score, "userId", callerId));

        List<String> blockingReasons = new ArrayList<>();
        if (!canEnterDiscovery) blockingReasons.add("PRIMARY_PHOTO_PENDING_REVIEW");

        return new OnboardingStatus(true, true, true, true, true, "DONE",
                score, true, canEnterDiscovery, blockingReasons);
    }

    @Transactional
    public void recomputeScore(UUID userId) {
        profileRepository.findByUserId(userId).ifPresent(ignored -> {
            OnboardingStatus s = getStatus(userId);
            jdbc.update(
                    "UPDATE profiles SET profile_completion_score = :score WHERE user_id = :userId",
                    Map.of("score", s.profileCompletionScore(), "userId", userId));
        });
    }

    private int computeScore(Profile profile, UUID userId,
                              boolean basicProfile, boolean location, boolean photo, boolean preferences) {
        int score = 0;
        if (basicProfile)   score += 25;
        if (location)       score += 10;
        if (photo)          score += 10;
        if (preferences)    score += 10;
        if (profile != null) {
            if (notBlank(profile.getBio()) && profile.getBio().length() >= 20) score += 10;
            if (profile.getHeightCm() != null)      score += 5;
            if (notBlank(profile.getReligion()))     score += 5;
            if (notBlank(profile.getEducationLevel())) score += 5;
            if (notBlank(profile.getNationality()))  score += 5;
            if (notBlank(profile.getEthnicity()))    score += 5;
        }
        Integer extraPhotos = jdbc.queryForObject(
                "SELECT COUNT(*) FROM profile_photos WHERE user_id = :uid AND is_primary = FALSE AND moderation_status <> 'REJECTED'",
                Map.of("uid", userId), Integer.class);
        if (extraPhotos != null && extraPhotos > 0) score += 10;
        return Math.min(score, 100);
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
