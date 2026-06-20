package com.qaliye.backend.user;

import com.qaliye.backend.onboarding.OnboardingService;
import com.qaliye.backend.user.entity.Profile;
import com.qaliye.backend.user.repository.ProfileRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class MeService {

    private final ProfileRepository profileRepository;
    private final OnboardingService onboardingService;
    private final UserStatusService userStatusService;

    public MeService(ProfileRepository profileRepository,
                     OnboardingService onboardingService,
                     UserStatusService userStatusService) {
        this.profileRepository = profileRepository;
        this.onboardingService = onboardingService;
        this.userStatusService = userStatusService;
    }

    public Map<String, Object> getMe(UUID userId) {
        UserStatusService.UserStatus userStatus = userStatusService.getStatus(userId);
        Profile profile = profileRepository.findByUserId(userId).orElse(null);
        OnboardingService.OnboardingStatus onboardingStatus = onboardingService.getStatus(userId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user_id", userId.toString());
        result.put("status", userStatus != null ? userStatus.status() : "ACTIVE");
        result.put("role", userStatus != null ? userStatus.role() : "USER");
        result.put("preferred_language", userStatus != null ? userStatus.preferredLanguage() : "en");

        if (profile == null) {
            result.put("profile", null);
        } else {
            Map<String, Object> profileMap = new LinkedHashMap<>();
            profileMap.put("exists", true);
            profileMap.put("display_name", profile.getDisplayName());
            profileMap.put("is_onboarded", profile.getIsOnboarded());
            profileMap.put("profile_completion_score", onboardingStatus.profileCompletionScore());
            result.put("profile", profileMap);
        }

        Map<String, Object> onboarding = new LinkedHashMap<>();
        onboarding.put("has_profile", profile != null);
        onboarding.put("is_onboarded", onboardingStatus.isOnboarded());
        onboarding.put("next_step", onboardingStatus.nextStep());
        onboarding.put("can_complete_onboarding", onboardingStatus.canCompleteOnboarding());
        onboarding.put("can_enter_discovery", onboardingStatus.canEnterDiscovery());
        onboarding.put("blocking_reasons", onboardingStatus.blockingReasons());
        result.put("onboarding", onboarding);

        return result;
    }
}
