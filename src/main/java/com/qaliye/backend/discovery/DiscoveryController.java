package com.qaliye.backend.discovery;

import com.qaliye.backend.common.CallerUtils;
import com.qaliye.backend.onboarding.OnboardingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/discovery")
public class DiscoveryController {

    private static final Set<String> VALID_GENDERS = Set.of("MALE", "FEMALE");

    private final DiscoveryService discoveryService;
    private final OnboardingService onboardingService;
    private final NamedParameterJdbcTemplate jdbc;

    public DiscoveryController(DiscoveryService discoveryService,
                               OnboardingService onboardingService,
                               NamedParameterJdbcTemplate jdbc) {
        this.discoveryService = discoveryService;
        this.onboardingService = onboardingService;
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
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT interested_in_gender, min_age, max_age, max_distance_km,
                       preferred_residency_types,
                       open_to_long_distance, open_to_relocation, show_verified_only
                FROM discovery_preferences
                WHERE user_id = :userId
                """, Map.of("userId", callerId));
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PREFERENCES_REQUIRED");
        }
        Map<String, Object> pref = rows.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("interested_in_gender", pref.get("interested_in_gender"));
        result.put("min_age", pref.get("min_age"));
        result.put("max_age", pref.get("max_age"));
        result.put("max_distance_km", pref.get("max_distance_km"));
        result.put("preferred_residency_types", pref.get("preferred_residency_types"));
        result.put("open_to_long_distance", pref.get("open_to_long_distance"));
        result.put("open_to_relocation", pref.get("open_to_relocation"));
        result.put("show_verified_only", pref.get("show_verified_only"));
        return ResponseEntity.ok(result);
    }

    @PutMapping("/preferences")
    public ResponseEntity<Map<String, Object>> updatePreferences(
            @Valid @RequestBody UpdatePreferencesRequest request) {
        UUID callerId = CallerUtils.callerId();

        if (!VALID_GENDERS.contains(request.interestedInGender())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_GENDER");
        }
        if (request.minAge() > request.maxAge()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_AGE_RANGE");
        }

        boolean openToLongDistance = request.openToLongDistance() != null && request.openToLongDistance();
        boolean openToRelocation = request.openToRelocation() != null && request.openToRelocation();
        boolean showVerifiedOnly = request.showVerifiedOnly() != null && request.showVerifiedOnly();

        List<String> preferredResidencyTypes = request.preferredResidencyTypes();
        if (preferredResidencyTypes == null || preferredResidencyTypes.isEmpty()) {
            preferredResidencyTypes = List.of("ETHIOPIA", "ERITREA", "DIASPORA");
        }
        String residencyArray = "{" + String.join(",", preferredResidencyTypes) + "}";

        org.springframework.jdbc.core.namedparam.MapSqlParameterSource params =
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                        .addValue("userId", callerId)
                        .addValue("gender", request.interestedInGender())
                        .addValue("minAge", request.minAge())
                        .addValue("maxAge", request.maxAge())
                        .addValue("maxDist", request.maxDistanceKm())
                        .addValue("residencyTypes", residencyArray)
                        .addValue("openToLongDistance", openToLongDistance)
                        .addValue("openToRelocation", openToRelocation)
                        .addValue("showVerifiedOnly", showVerifiedOnly);

        jdbc.update("""
                INSERT INTO discovery_preferences
                    (user_id, interested_in_gender, min_age, max_age, max_distance_km,
                     preferred_residency_types,
                     open_to_long_distance, open_to_relocation, show_verified_only)
                VALUES (:userId, :gender, :minAge, :maxAge, :maxDist,
                        :residencyTypes::text[],
                        :openToLongDistance, :openToRelocation, :showVerifiedOnly)
                ON CONFLICT (user_id) DO UPDATE SET
                    interested_in_gender      = EXCLUDED.interested_in_gender,
                    min_age                   = EXCLUDED.min_age,
                    max_age                   = EXCLUDED.max_age,
                    max_distance_km           = EXCLUDED.max_distance_km,
                    preferred_residency_types = EXCLUDED.preferred_residency_types,
                    open_to_long_distance     = EXCLUDED.open_to_long_distance,
                    open_to_relocation        = EXCLUDED.open_to_relocation,
                    show_verified_only        = EXCLUDED.show_verified_only,
                    updated_at                = NOW()
                """, params);

        OnboardingService.OnboardingStatus status = onboardingService.getStatus(callerId);

        Map<String, Object> preferences = new LinkedHashMap<>();
        preferences.put("interested_in_gender", request.interestedInGender());
        preferences.put("min_age", request.minAge());
        preferences.put("max_age", request.maxAge());
        preferences.put("max_distance_km", request.maxDistanceKm());
        preferences.put("preferred_residency_types", preferredResidencyTypes);
        preferences.put("open_to_long_distance", openToLongDistance);
        preferences.put("open_to_relocation", openToRelocation);
        preferences.put("show_verified_only", showVerifiedOnly);

        Map<String, Object> onboarding = new LinkedHashMap<>();
        onboarding.put("next_step", status.nextStep());
        onboarding.put("can_complete_onboarding", status.canCompleteOnboarding());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("preferences", preferences);
        response.put("onboarding", onboarding);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/preferences")
    public ResponseEntity<Map<String, Object>> resetPreferences() {
        UUID callerId = CallerUtils.callerId();
        // interested_in_gender has no valid default (MALE/FEMALE only, must be explicitly chosen).
        // Deleting the row forces the user to re-select preferences before entering discovery.
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
