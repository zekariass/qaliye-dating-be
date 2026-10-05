package com.qaliye.backend.matchmaking.service;

import com.qaliye.backend.matchmaking.repository.MatchmakingPreferenceRepository.PreferenceValues;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Shared validation/normalization for matchmaking preference values.
 *
 * <p>Used by {@link MatchmakingPreferenceService} (user upsert) and
 * {@link MatchmakingCandidateSearchService} (snapshot + admin overrides) so
 * the same casing/range rules apply regardless of entry point. Mirrors the
 * V70 CHECK constraints so bad input fails with 422, not a 500.
 */
final class MatchmakingPreferenceNormalizer {

    private MatchmakingPreferenceNormalizer() {}

    static final String[] EMPTY_STRINGS = new String[0];
    static final UUID[] EMPTY_UUIDS = new UUID[0];

    private static final Set<String> HAS_CHILDREN_VALUES =
            Set.of("any", "yes", "no");
    private static final Set<String> WANTS_CHILDREN_VALUES =
            Set.of("any", "yes", "no", "not_sure", "open_to_discussion");
    private static final Set<String> MARITAL_VALUES =
            Set.of("NEVER_MARRIED", "DIVORCED", "WIDOWED", "SEPARATED");
    private static final Set<String> SMOKING_VALUES =
            Set.of("NO", "YES", "OCCASIONALLY", "TRYING_TO_QUIT");
    private static final Set<String> DRINKING_VALUES =
            Set.of("NO", "SOCIALLY", "OCCASIONALLY", "YES");
    private static final Set<String> MARRIAGE_TIMELINE_VALUES =
            Set.of("NOW", "ONE_TO_TWO_YEARS", "THREE_TO_FIVE_YEARS", "NOT_SURE");
    private static final Set<String> YES_NO_VALUES = Set.of("YES", "NO");

    static PreferenceValues normalize(PreferenceValues v) {
        if (v.minAge() != null && v.minAge() < 18) {
            throw unprocessable("minAge must be >= 18");
        }
        if (v.maxAge() != null && v.maxAge() > 120) {
            throw unprocessable("maxAge must be <= 120");
        }
        if (v.minAge() != null && v.maxAge() != null && v.minAge() > v.maxAge()) {
            throw unprocessable("minAge must be <= maxAge");
        }
        if (v.minHeightCm() != null && v.minHeightCm() <= 0) {
            throw unprocessable("minHeightCm must be > 0");
        }
        if (v.minHeightCm() != null && v.maxHeightCm() != null
                && v.minHeightCm() > v.maxHeightCm()) {
            throw unprocessable("minHeightCm must be <= maxHeightCm");
        }

        return new PreferenceValues(
                v.minAge(), v.maxAge(), v.minHeightCm(), v.maxHeightCm(),
                normalizeCountryCodes(v.specificCountryCodes()),
                normalizeEnum(v.hasChildrenPreference(), HAS_CHILDREN_VALUES,
                        "hasChildrenPreference"),
                normalizeEnum(v.wantsChildrenPreference(), WANTS_CHILDREN_VALUES,
                        "wantsChildrenPreference"),
                orEmpty(v.religionPreferences()),
                orEmpty(v.educationLevels()),
                normalizeEnumArray(v.maritalStatuses(), MARITAL_VALUES, "maritalStatuses"),
                normalizeEnumArray(v.smokingPreferences(), SMOKING_VALUES, "smokingPreferences"),
                normalizeEnumArray(v.drinkingPreferences(), DRINKING_VALUES, "drinkingPreferences"),
                orEmpty(v.languagePreferenceIds()),
                orEmpty(v.ethnicityPreferenceIds()),
                v.userNotes(),
                normalizeNullableUpperEnum(v.marriageTimeline(), MARRIAGE_TIMELINE_VALUES,
                        "marriageTimeline"),
                normalizeNullableUpperEnum(v.longDistanceRelationship(), YES_NO_VALUES,
                        "longDistanceRelationship"),
                normalizeNullableUpperEnum(v.familyInvolvement(), YES_NO_VALUES,
                        "familyInvolvement"),
                normalizeNullableUpperEnum(v.religionImportant(), YES_NO_VALUES,
                        "religionImportant"),
                normalizeNullableUpperEnum(v.willingToRelocate(), YES_NO_VALUES,
                        "willingToRelocate"));
    }

    private static String normalizeEnum(String value, Set<String> allowed, String field) {
        if (value == null || value.isBlank()) return "any";
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw unprocessable(field + " must be one of " + allowed);
        }
        return normalized;
    }

    private static String[] normalizeEnumArray(String[] values, Set<String> allowed,
                                               String field) {
        if (values == null) return EMPTY_STRINGS;
        return Arrays.stream(values)
                .map(s -> s.toUpperCase(Locale.ROOT))
                .peek(s -> {
                    if (!allowed.contains(s)) {
                        throw unprocessable(field + " contains invalid value: " + s);
                    }
                })
                .toArray(String[]::new);
    }

    private static String[] normalizeCountryCodes(String[] codes) {
        if (codes == null) return EMPTY_STRINGS;
        return Arrays.stream(codes)
                .map(c -> c.toUpperCase(Locale.ROOT))
                .toArray(String[]::new);
    }

    private static String[] orEmpty(String[] arr) {
        return arr == null ? EMPTY_STRINGS : arr;
    }

    private static UUID[] orEmpty(UUID[] arr) {
        return arr == null ? EMPTY_UUIDS : arr;
    }

    /** Nullable alignment field: null/blank → null; otherwise must be in allowed (UPPERCASE). */
    static String normalizeNullableUpperEnum(String value, Set<String> allowed, String field) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw unprocessable(field + " must be one of " + allowed);
        }
        return normalized;
    }

    private static ResponseStatusException unprocessable(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
