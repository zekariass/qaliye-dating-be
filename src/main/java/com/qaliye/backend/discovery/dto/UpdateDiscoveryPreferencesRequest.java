package com.qaliye.backend.discovery.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record UpdateDiscoveryPreferencesRequest(
        @NotNull @Pattern(regexp = "MALE|FEMALE") String interestedInGender,
        @NotNull @Min(18) @Max(100) Integer minAge,
        @Min(18) @Max(120) Integer maxAge,
        @Min(1) @Max(500) Integer maxDistanceKm,
        Boolean showVerifiedOnly,
        @Pattern(regexp = "nearby|diaspora|specific_countries|anywhere") String locationMode,
        @Size(max = 20) List<String> specificCountryCodes,
        Boolean expandSearchWhenLimited,
        @Pattern(regexp = "any|yes|no") String hasChildrenPreference,
        @Pattern(regexp = "any|yes|no|not_sure|open_to_discussion") String wantsChildrenPreference,
        @Size(max = 10) List<String> religionPreferences,
        @Size(max = 20) List<UUID> languagePreferenceIds,
        @Size(max = 10) List<UUID> ethnicityPreferenceIds
) {}
