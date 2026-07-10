package com.qaliye.backend.discovery;

import com.qaliye.backend.common.CallerUtils;
import com.qaliye.backend.discovery.dto.UpdateDiscoveryPreferencesRequest;
import com.qaliye.backend.onboarding.OnboardingService;
import com.qaliye.backend.profile.ProfileService;
import com.qaliye.backend.profile.dto.DiscoveryPreferencesDto;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/discovery")
public class DiscoveryController {

    private final DiscoveryService discoveryService;
    private final OnboardingService onboardingService;
    private final ProfileService profileService;
    private final NamedParameterJdbcTemplate jdbc;

    public DiscoveryController(DiscoveryService discoveryService,
                               OnboardingService onboardingService,
                               ProfileService profileService,
                               NamedParameterJdbcTemplate jdbc) {
        this.discoveryService = discoveryService;
        this.onboardingService = onboardingService;
        this.profileService = profileService;
        this.jdbc = jdbc;
    }

    @GetMapping("/cards")
    public ResponseEntity<Map<String, Object>> cards(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) String scope) {
        UUID callerId = CallerUtils.callerId();
        DiscoveryService.DiscoveryResult result = discoveryService.getCards(callerId, cursor, limit, scope);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("cards", result.cards());
        response.put("next_cursor", result.nextCursor());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/preferences")
    public ResponseEntity<Map<String, Object>> getPreferences() {
        UUID callerId = CallerUtils.callerId();
        DiscoveryPreferencesDto prefs = profileService.getPreferences(callerId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("preferences", prefs);
        return ResponseEntity.ok(result);
    }

    @PutMapping("/preferences")
    public ResponseEntity<Map<String, Object>> updatePreferences(
            @Valid @RequestBody UpdateDiscoveryPreferencesRequest request) {
        UUID callerId = CallerUtils.callerId();
        DiscoveryPreferencesDto prefs = profileService.updatePreferences(callerId, request);

        OnboardingService.OnboardingStatus status = onboardingService.getStatus(callerId);

        Map<String, Object> onboarding = new LinkedHashMap<>();
        onboarding.put("next_step", status.nextStep());
        onboarding.put("can_complete_onboarding", status.canCompleteOnboarding());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("preferences", prefs);
        response.put("onboarding", onboarding);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/preferences")
    public ResponseEntity<Map<String, Object>> resetPreferences() {
        UUID callerId = CallerUtils.callerId();
        jdbc.update("DELETE FROM discovery_preferences WHERE user_id = :userId",
                Map.of("userId", callerId));

        OnboardingService.OnboardingStatus status = onboardingService.getStatus(callerId);

        Map<String, Object> onboarding = new LinkedHashMap<>();
        onboarding.put("next_step", status.nextStep());
        onboarding.put("can_complete_onboarding", status.canCompleteOnboarding());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("preferences", null);
        result.put("onboarding", onboarding);
        return ResponseEntity.ok(result);
    }
}
