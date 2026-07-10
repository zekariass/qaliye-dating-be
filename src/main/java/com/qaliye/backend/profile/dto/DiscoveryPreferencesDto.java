package com.qaliye.backend.profile.dto;

import com.qaliye.backend.catalog.EthnicityOption;
import com.qaliye.backend.catalog.LanguageOption;

import java.util.List;

public record DiscoveryPreferencesDto(
        String interestedInGender,
        Integer minAge,
        Integer maxAge,
        Integer maxDistanceKm,
        Boolean showVerifiedOnly,
        String locationMode,
        List<String> specificCountryCodes,
        Boolean expandSearchWhenLimited,
        String hasChildrenPreference,
        String wantsChildrenPreference,
        List<String> religionPreferences,
        List<LanguageOption> languagePreferences,
        List<EthnicityOption> ethnicityPreferences,
        Integer preferencesVersion
) {}
