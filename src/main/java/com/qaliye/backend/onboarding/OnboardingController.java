package com.qaliye.backend.onboarding;

import com.qaliye.backend.common.CallerUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/onboarding")
public class OnboardingController {

    private final OnboardingService onboardingService;

    public OnboardingController(OnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        UUID callerId = CallerUtils.callerId();
        OnboardingService.OnboardingStatus s = onboardingService.getStatus(callerId);

        Map<String, Object> steps = new LinkedHashMap<>();
        steps.put("basic_profile", s.basicProfile());
        steps.put("location", s.location());
        steps.put("photo", s.photo());
        steps.put("preferences", s.preferences());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("is_onboarded", s.isOnboarded());
        resp.put("next_step", s.nextStep());
        resp.put("steps", steps);
        resp.put("profile_completion_score", s.profileCompletionScore());
        resp.put("can_complete_onboarding", s.canCompleteOnboarding());
        resp.put("can_enter_discovery", s.canEnterDiscovery());
        resp.put("blocking_reasons", s.blockingReasons());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/complete")
    public ResponseEntity<Map<String, Object>> complete() {
        UUID callerId = CallerUtils.callerId();
        OnboardingService.OnboardingStatus s = onboardingService.complete(callerId);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("is_onboarded", true);
        resp.put("profile_completion_score", s.profileCompletionScore());
        resp.put("can_enter_discovery", s.canEnterDiscovery());
        resp.put("blocking_reasons", s.blockingReasons());
        return ResponseEntity.ok(resp);
    }
}
