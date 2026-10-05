package com.qaliye.backend.matchmaking.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MatchmakingPreferenceRepository {

    /**
     * User-editable preference values. {@code gender} is deliberately absent —
     * it is derived as the opposite of the user's profile gender at write
     * time (sought partner gender; matchmaking is hetero), never client-set.
     * NULL scalar = inactive; empty array / 'any' enum = no constraint.
     */
    public record PreferenceValues(
            Integer minAge,
            Integer maxAge,
            Integer minHeightCm,
            Integer maxHeightCm,
            String[] specificCountryCodes,
            String hasChildrenPreference,
            String wantsChildrenPreference,
            String[] religionPreferences,
            String[] educationLevels,
            String[] maritalStatuses,
            String[] smokingPreferences,
            String[] drinkingPreferences,
            UUID[] languagePreferenceIds,
            UUID[] ethnicityPreferenceIds,
            String userNotes,
            String marriageTimeline,
            String longDistanceRelationship,
            String familyInvolvement,
            String religionImportant,
            String willingToRelocate) {}

    public record PreferenceRow(
            UUID id,
            UUID userId,
            int version,
            String gender,
            Integer minAge,
            Integer maxAge,
            Integer minHeightCm,
            Integer maxHeightCm,
            String[] specificCountryCodes,
            String hasChildrenPreference,
            String wantsChildrenPreference,
            String[] religionPreferences,
            String[] educationLevels,
            String[] maritalStatuses,
            String[] smokingPreferences,
            String[] drinkingPreferences,
            UUID[] languagePreferenceIds,
            UUID[] ethnicityPreferenceIds,
            String matchmakerNotes,
            String userNotes,
            String marriageTimeline,
            String longDistanceRelationship,
            String familyInvolvement,
            String religionImportant,
            String willingToRelocate,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    private static final String SELECT_COLS = """
            id, user_id, version, gender, min_age, max_age,
            min_height_cm, max_height_cm, specific_country_codes,
            has_children_preference, wants_children_preference,
            religion_preferences, education_levels, marital_statuses,
            smoking_preferences, drinking_preferences,
            language_preference_ids, ethnicity_preference_ids,
            matchmaker_notes, user_notes,
            marriage_timeline, long_distance_relationship,
            family_involvement, religion_important, willing_to_relocate,
            created_at, updated_at
            """;

    // gender is the SOUGHT partner gender — the opposite of profiles.gender
    // (MALE → FEMALE, FEMALE → MALE, other/NULL → NULL), derived on both
    // insert and update. Never user-editable; documents the search direction.
    // matchmaker_notes is intentionally untouched here: admin-only field,
    // updated separately via updateNotes (a user upsert must not wipe it).
    private static final String UPSERT_SQL = """
            INSERT INTO matchmaking_preferences (
                user_id, version, gender,
                min_age, max_age, min_height_cm, max_height_cm,
                specific_country_codes,
                has_children_preference, wants_children_preference,
                religion_preferences, education_levels, marital_statuses,
                smoking_preferences, drinking_preferences,
                language_preference_ids, ethnicity_preference_ids,
                user_notes,
                marriage_timeline, long_distance_relationship,
                family_involvement, religion_important, willing_to_relocate)
            VALUES (
                :userId, 1,
                CASE (SELECT gender FROM profiles WHERE user_id = :userId)
                     WHEN 'MALE' THEN 'FEMALE'
                     WHEN 'FEMALE' THEN 'MALE'
                     ELSE NULL END,
                :minAge, :maxAge, :minHeightCm, :maxHeightCm,
                :specificCountryCodes::text[],
                :hasChildrenPreference, :wantsChildrenPreference,
                :religionPreferences::text[], :educationLevels::text[],
                :maritalStatuses::text[],
                :smokingPreferences::text[], :drinkingPreferences::text[],
                :languagePreferenceIds::uuid[], :ethnicityPreferenceIds::uuid[],
                :userNotes,
                :marriageTimeline, :longDistanceRelationship,
                :familyInvolvement, :religionImportant, :willingToRelocate)
            ON CONFLICT (user_id) DO UPDATE
                SET gender                      = CASE (SELECT gender FROM profiles
                                                        WHERE user_id = :userId)
                                                      WHEN 'MALE' THEN 'FEMALE'
                                                      WHEN 'FEMALE' THEN 'MALE'
                                                      ELSE NULL END,
                    min_age                     = EXCLUDED.min_age,
                    max_age                     = EXCLUDED.max_age,
                    min_height_cm               = EXCLUDED.min_height_cm,
                    max_height_cm               = EXCLUDED.max_height_cm,
                    specific_country_codes      = EXCLUDED.specific_country_codes,
                    has_children_preference     = EXCLUDED.has_children_preference,
                    wants_children_preference   = EXCLUDED.wants_children_preference,
                    religion_preferences        = EXCLUDED.religion_preferences,
                    education_levels            = EXCLUDED.education_levels,
                    marital_statuses            = EXCLUDED.marital_statuses,
                    smoking_preferences         = EXCLUDED.smoking_preferences,
                    drinking_preferences        = EXCLUDED.drinking_preferences,
                    language_preference_ids     = EXCLUDED.language_preference_ids,
                    ethnicity_preference_ids    = EXCLUDED.ethnicity_preference_ids,
                    user_notes                  = EXCLUDED.user_notes,
                    marriage_timeline           = EXCLUDED.marriage_timeline,
                    long_distance_relationship  = EXCLUDED.long_distance_relationship,
                    family_involvement          = EXCLUDED.family_involvement,
                    religion_important          = EXCLUDED.religion_important,
                    willing_to_relocate         = EXCLUDED.willing_to_relocate,
                    version                     = matchmaking_preferences.version + 1,
                    updated_at                  = CURRENT_TIMESTAMP
            RETURNING id
            """;

    private static final String UPDATE_NOTES_SQL = """
            UPDATE matchmaking_preferences
            SET matchmaker_notes = :notes,
                updated_at = CURRENT_TIMESTAMP
            WHERE user_id = :userId
            """;

    private static final String REFRESH_GENDER_SQL = """
            UPDATE matchmaking_preferences
            SET gender = CASE (SELECT gender FROM profiles WHERE user_id = :userId)
                         WHEN 'MALE' THEN 'FEMALE'
                         WHEN 'FEMALE' THEN 'MALE'
                         ELSE NULL END
            WHERE user_id = :userId
            """;

    private static final String FIND_BY_USER_SQL = """
            SELECT %s FROM matchmaking_preferences
            WHERE user_id = :userId
            """.formatted(SELECT_COLS);

    private static final String FIND_BY_ID_SQL = """
            SELECT %s FROM matchmaking_preferences
            WHERE id = :id
            """.formatted(SELECT_COLS);

    private final NamedParameterJdbcTemplate jdbc;

    public MatchmakingPreferenceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID upsert(UUID userId, PreferenceValues values) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("minAge", values.minAge())
                .addValue("maxAge", values.maxAge())
                .addValue("minHeightCm", values.minHeightCm())
                .addValue("maxHeightCm", values.maxHeightCm())
                .addValue("specificCountryCodes", values.specificCountryCodes())
                .addValue("hasChildrenPreference", values.hasChildrenPreference())
                .addValue("wantsChildrenPreference", values.wantsChildrenPreference())
                .addValue("religionPreferences", values.religionPreferences())
                .addValue("educationLevels", values.educationLevels())
                .addValue("maritalStatuses", values.maritalStatuses())
                .addValue("smokingPreferences", values.smokingPreferences())
                .addValue("drinkingPreferences", values.drinkingPreferences())
                .addValue("languagePreferenceIds", values.languagePreferenceIds())
                .addValue("ethnicityPreferenceIds", values.ethnicityPreferenceIds())
                .addValue("userNotes", values.userNotes())
                .addValue("marriageTimeline", values.marriageTimeline())
                .addValue("longDistanceRelationship", values.longDistanceRelationship())
                .addValue("familyInvolvement", values.familyInvolvement())
                .addValue("religionImportant", values.religionImportant())
                .addValue("willingToRelocate", values.willingToRelocate());
        return jdbc.query(UPSERT_SQL, params, (rs, i) -> rs.getObject("id", UUID.class))
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Failed to upsert matchmaking preferences"));
    }

    public boolean updateNotes(UUID userId, String notes) {
        return jdbc.update(UPDATE_NOTES_SQL,
                new MapSqlParameterSource("userId", userId).addValue("notes", notes)) > 0;
    }

    /** Re-derives the cached sought-gender column from the live profile. */
    public void refreshGender(UUID userId) {
        jdbc.update(REFRESH_GENDER_SQL, new MapSqlParameterSource("userId", userId));
    }

    public Optional<PreferenceRow> findByUserId(UUID userId) {
        return jdbc.query(FIND_BY_USER_SQL, new MapSqlParameterSource("userId", userId), this::mapRow)
                .stream().findFirst();
    }

    public Optional<PreferenceRow> findById(UUID id) {
        return jdbc.query(FIND_BY_ID_SQL, new MapSqlParameterSource("id", id), this::mapRow)
                .stream().findFirst();
    }

    // ── Mappers ────────────────────────────────────────────────────────────

    private PreferenceRow mapRow(ResultSet rs, int i) throws SQLException {
        return new PreferenceRow(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getInt("version"),
                rs.getString("gender"),
                rs.getObject("min_age", Integer.class),
                rs.getObject("max_age", Integer.class),
                rs.getObject("min_height_cm", Integer.class),
                rs.getObject("max_height_cm", Integer.class),
                toStringArray(rs.getArray("specific_country_codes")),
                rs.getString("has_children_preference"),
                rs.getString("wants_children_preference"),
                toStringArray(rs.getArray("religion_preferences")),
                toStringArray(rs.getArray("education_levels")),
                toStringArray(rs.getArray("marital_statuses")),
                toStringArray(rs.getArray("smoking_preferences")),
                toStringArray(rs.getArray("drinking_preferences")),
                toUuidArray(rs.getArray("language_preference_ids")),
                toUuidArray(rs.getArray("ethnicity_preference_ids")),
                rs.getString("matchmaker_notes"),
                rs.getString("user_notes"),
                rs.getString("marriage_timeline"),
                rs.getString("long_distance_relationship"),
                rs.getString("family_involvement"),
                rs.getString("religion_important"),
                rs.getString("willing_to_relocate"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private String[] toStringArray(java.sql.Array arr) throws SQLException {
        if (arr == null) return new String[0];
        return (String[]) arr.getArray();
    }

    private UUID[] toUuidArray(java.sql.Array arr) throws SQLException {
        if (arr == null) return new UUID[0];
        return (UUID[]) arr.getArray();
    }
}
