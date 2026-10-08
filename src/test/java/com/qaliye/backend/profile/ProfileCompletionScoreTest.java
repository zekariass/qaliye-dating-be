package com.qaliye.backend.profile;

import com.qaliye.backend.moderation.PhotoModerationService;
import com.qaliye.backend.storage.SupabaseStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ProfilePhotoService.computeScore.
 *
 * Total denominator is 28:
 *   6  identity fields    (display_name, gender, date_of_birth, height_cm, residency_type, address_id)
 *   10 about-me fields    (bio, ethnicity_ids, nationality, religion, education_level, occupation,
 *                          relationship_intention, marital_status, has_children, wants_children)
 *   5  lifestyle fields   (smoking_detail, drinking_detail, activity_level, interests, language_ids)
 *   5  marriage-pref fields (marriage_timeline, long_distance_relationship, family_involvement,
 *                            religion_importance, willing_to_relocate)
 *   2  existence checks   (discovery_preferences row, profile_photos row)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProfileCompletionScoreTest {

    @Mock NamedParameterJdbcTemplate jdbc;
    @Mock SupabaseStorageService storageService;
    @Mock PhotoModerationService photoModerationService;

    ProfilePhotoService service;

    @BeforeEach
    void setUp() {
        service = new ProfilePhotoService(jdbc, storageService, photoModerationService);
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private void stubProfileRow(UUID userId, Map<String, Object> row) {
        when(jdbc.queryForList(contains("p.display_name"), anyMap())).thenReturn(List.of(row));
    }

    private void stubExistenceQueries(boolean hasPrefs, boolean hasPhotos) {
        doAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("discovery_preferences")) return hasPrefs;
            if (sql.contains("profile_photos"))        return hasPhotos;
            return false;
        }).when(jdbc).queryForObject(anyString(), anyMap(), any(Class.class));
    }

    /**
     * Creates a mocked java.sql.Array containing the given items.
     * isNonEmptyArray() in ProfilePhotoService handles java.sql.Array via getArray().
     */
    private java.sql.Array sqlArray(Object[] items) {
        java.sql.Array arr = mock(java.sql.Array.class);
        try {
            when(arr.getArray()).thenReturn(items);
        } catch (Exception ignored) {}
        return arr;
    }

    /** Row with every one of the 26 direct profile/address columns filled. */
    private Map<String, Object> allFieldsRow() {
        Map<String, Object> r = new HashMap<>();
        r.put("display_name", "Jane Doe");
        r.put("gender", "FEMALE");
        r.put("date_of_birth", java.sql.Date.valueOf("1995-06-15"));
        r.put("height_cm", 165);
        r.put("residency_type", "ETHIOPIA");
        r.put("address_id", UUID.randomUUID());
        r.put("bio", "I love hiking and cooking.");
        r.put("ethnicity_ids", sqlArray(new UUID[]{UUID.randomUUID()}));
        r.put("nationality", "Ethiopian");
        r.put("religion", "CHRISTIAN_ORTHODOX");
        r.put("education_level", "BACHELORS");
        r.put("occupation", "Engineer");
        r.put("relationship_intention", "MARRIAGE");
        r.put("marital_status", "SINGLE");
        r.put("has_children", false);
        r.put("wants_children", true);
        r.put("smoking_detail", "NO");
        r.put("drinking_detail", "NO");
        r.put("activity_level", "ACTIVE");
        r.put("interests", new String[]{"Reading", "Cooking"});
        r.put("language_ids", sqlArray(new UUID[]{UUID.randomUUID()}));
        r.put("marriage_timeline", "WITHIN_1_YEAR");
        r.put("long_distance_relationship", "MAYBE");
        r.put("family_involvement", "VERY_IMPORTANT");
        r.put("religion_importance", "VERY_IMPORTANT");
        r.put("willing_to_relocate", "YES");
        return r;
    }

    /** Row with none of the optional/marriage fields. */
    private Map<String, Object> minimalRow() {
        Map<String, Object> r = new HashMap<>();
        r.put("display_name", null);
        r.put("gender", null);
        r.put("date_of_birth", null);
        r.put("height_cm", null);
        r.put("residency_type", null);
        r.put("address_id", null);
        r.put("bio", null);
        r.put("ethnicity_ids", null);
        r.put("nationality", null);
        r.put("religion", null);
        r.put("education_level", null);
        r.put("occupation", null);
        r.put("relationship_intention", null);
        r.put("marital_status", null);
        r.put("has_children", null);
        r.put("wants_children", null);
        r.put("smoking_detail", null);
        r.put("drinking_detail", null);
        r.put("activity_level", null);
        r.put("interests", null);
        r.put("language_ids", null);
        r.put("marriage_timeline", null);
        r.put("long_distance_relationship", null);
        r.put("family_involvement", null);
        r.put("religion_importance", null);
        r.put("willing_to_relocate", null);
        return r;
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    void emptyProfile_returnsZero() {
        UUID userId = UUID.randomUUID();
        when(jdbc.queryForList(contains("p.display_name"), anyMap())).thenReturn(List.of());
        assertThat(service.computeScore(userId)).isEqualTo(0);
    }

    @Test
    void allDirectFields_withoutExistenceChecks_returnsNinetyThree() {
        UUID userId = UUID.randomUUID();
        stubProfileRow(userId, allFieldsRow());
        stubExistenceQueries(false, false);
        // 26 direct profile/address fields filled, 2 existence checks absent = 26/28 ≈ 93
        int expected = (int) Math.round((26 / 28.0) * 100);
        assertThat(service.computeScore(userId)).isEqualTo(expected);
    }

    @Test
    void noFieldsFilled_noExistenceChecks_returnsZero() {
        UUID userId = UUID.randomUUID();
        stubProfileRow(userId, minimalRow());
        stubExistenceQueries(false, false);
        assertThat(service.computeScore(userId)).isEqualTo(0);
    }

    @Test
    void legacyFieldsOnly_noMarriageFields_returnsCorrectScore() {
        // 21 direct legacy fields + 2 existence = 23 filled out of 28
        UUID userId = UUID.randomUUID();
        Map<String, Object> row = allFieldsRow();
        row.put("marriage_timeline", null);
        row.put("long_distance_relationship", null);
        row.put("family_involvement", null);
        row.put("religion_importance", null);
        row.put("willing_to_relocate", null);
        stubProfileRow(userId, row);
        stubExistenceQueries(false, false);

        int score = service.computeScore(userId);
        // 21 legacy direct fields only (no marriage fields, no existence checks) = 21/28 ≈ 75
        int expected = (int) Math.round((21 / 28.0) * 100);
        assertThat(score).isEqualTo(expected);
    }

    @Test
    void onlyMarriageFields_returnsCorrectScore() {
        // 5 marriage fields only (no existence checks, no other fields)
        UUID userId = UUID.randomUUID();
        Map<String, Object> row = minimalRow();
        row.put("marriage_timeline", "WITHIN_6_MONTHS");
        row.put("long_distance_relationship", "YES");
        row.put("family_involvement", "IMPORTANT");
        row.put("religion_importance", "SOMEWHAT_IMPORTANT");
        row.put("willing_to_relocate", "NO");
        stubProfileRow(userId, row);
        stubExistenceQueries(false, false);

        int score = service.computeScore(userId);
        int expected = (int) Math.round((5 / 28.0) * 100); // 18
        assertThat(score).isEqualTo(expected);
    }

    @Test
    void eachMarriageFieldContributesOnePoint() {
        UUID userId = UUID.randomUUID();
        stubExistenceQueries(false, false);

        // Base score: no fields filled at all
        stubProfileRow(userId, minimalRow());
        int baseScore = service.computeScore(userId); // 0

        String[] fields = {"marriage_timeline", "long_distance_relationship",
                "family_involvement", "religion_importance", "willing_to_relocate"};
        String[] values = {"NOT_SURE", "MAYBE", "IMPORTANT", "NOT_IMPORTANT", "MAYBE"};

        for (int i = 0; i < fields.length; i++) {
            Map<String, Object> row = new HashMap<>(minimalRow()); // fresh all-null base
            row.put(fields[i], values[i]);                         // set only this one field
            stubProfileRow(userId, row);
            int score = service.computeScore(userId);
            assertThat(score)
                    .describedAs("field '%s' should increase the score above the base", fields[i])
                    .isGreaterThan(baseScore);
        }
    }

    @Test
    void marriageTimeline_null_notCounted() {
        UUID userId = UUID.randomUUID();
        Map<String, Object> row = minimalRow();
        row.put("marriage_timeline", null);
        stubProfileRow(userId, row);
        stubExistenceQueries(false, false);
        assertThat(service.computeScore(userId)).isEqualTo(0);
    }

    @Test
    void allFiveMarriageFieldsSet_addsFivePoints() {
        UUID userId = UUID.randomUUID();
        stubExistenceQueries(false, false);

        Map<String, Object> withoutMarriage = minimalRow();
        stubProfileRow(userId, withoutMarriage);
        int withoutScore = service.computeScore(userId);

        Map<String, Object> withMarriage = new HashMap<>(minimalRow());
        withMarriage.put("marriage_timeline", "2_TO_5_YEARS");
        withMarriage.put("long_distance_relationship", "NO");
        withMarriage.put("family_involvement", "NOT_IMPORTANT");
        withMarriage.put("religion_importance", "NOT_IMPORTANT");
        withMarriage.put("willing_to_relocate", "YES");
        stubProfileRow(userId, withMarriage);
        int withScore = service.computeScore(userId);

        // 5 additional filled fields out of 28 total
        int expectedDiff = (int) Math.round((5 / 28.0) * 100);
        assertThat(withScore - withoutScore).isEqualTo(expectedDiff);
    }

    @Test
    void discoveryPreferences_existence_countsAsFilled() {
        UUID userId = UUID.randomUUID();
        stubProfileRow(userId, minimalRow());

        stubExistenceQueries(false, false);
        int without = service.computeScore(userId);

        stubExistenceQueries(true, false);
        int with = service.computeScore(userId);

        assertThat(with).isGreaterThan(without);
    }

    @Test
    void profilePhoto_existence_countsAsFilled() {
        UUID userId = UUID.randomUUID();
        stubProfileRow(userId, minimalRow());

        stubExistenceQueries(false, false);
        int without = service.computeScore(userId);

        stubExistenceQueries(false, true);
        int with = service.computeScore(userId);

        assertThat(with).isGreaterThan(without);
    }

    @Test
    void addingBothExistenceChecks_pushesScoreFrom93To100() {
        UUID userId = UUID.randomUUID();
        stubProfileRow(userId, allFieldsRow()); // 26 direct fields

        stubExistenceQueries(false, false);
        int without = service.computeScore(userId); // 26/28 = 93

        stubExistenceQueries(true, true);
        int with = service.computeScore(userId); // 28/28 = 100

        assertThat(without).isEqualTo((int) Math.round((26 / 28.0) * 100));
        assertThat(with).isEqualTo(100);
    }
}
