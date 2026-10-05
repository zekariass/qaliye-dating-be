package com.qaliye.backend.matchmaking.service;

import com.qaliye.backend.matchmaking.service.MatchmakingCompatibilityService.ProfileFacts;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Loads a user's resolved profile facts (age, height, country, religion,
 * children, languages, ethnicities, …) for compatibility scoring. Shared by
 * {@link MatchmakingCandidateSearchService} (requester side) and
 * {@link MatchmakingIntroductionService} (both sides at introduction
 * creation).
 */
@Component
final class MatchmakingUserFactsLoader {

    private static final String FACTS_SQL = """
            SELECT p.gender,
                   CASE WHEN p.date_of_birth IS NOT NULL
                        THEN EXTRACT(YEAR FROM AGE(p.date_of_birth))::INT END AS age,
                   p.height_cm, a.country_code,
                   p.has_children, p.wants_children, p.religion,
                   p.education_level, p.marital_status,
                   p.smoking, p.drinking, p.smoking_detail, p.drinking_detail,
                   p.language_ids, p.ethnicity_ids
            FROM profiles p
            LEFT JOIN app_users au ON au.id = p.user_id
            LEFT JOIN addresses a  ON a.id = au.address_id
            WHERE p.user_id = :userId
            """;

    private final NamedParameterJdbcTemplate jdbc;

    MatchmakingUserFactsLoader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Gender + full profile facts; throws 422 when the user has no profile. */
    FactRow load(UUID userId) {
        return jdbc.query(FACTS_SQL,
                        new MapSqlParameterSource("userId", userId),
                        this::mapRow)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Profile not found for user " + userId));
    }

    /** Gender + the profile facts the scorer consumes. */
    record FactRow(
            String gender,
            Integer age,
            Integer heightCm,
            String countryCode,
            Boolean hasChildren,
            Boolean wantsChildren,
            String religion,
            String educationLevel,
            String maritalStatus,
            String smokingValue,
            String drinkingValue,
            UUID[] languageIds,
            UUID[] ethnicityIds) {

        ProfileFacts toProfileFacts() {
            return new ProfileFacts(age, heightCm, countryCode, hasChildren,
                    wantsChildren, religion, educationLevel, maritalStatus,
                    smokingValue, drinkingValue, languageIds, ethnicityIds);
        }
    }

    private FactRow mapRow(ResultSet rs, int i) throws SQLException {
        return new FactRow(
                rs.getString("gender"),
                rs.getObject("age", Integer.class),
                rs.getObject("height_cm", Integer.class),
                rs.getString("country_code"),
                rs.getObject("has_children", Boolean.class),
                rs.getObject("wants_children", Boolean.class),
                rs.getString("religion"),
                rs.getString("education_level"),
                rs.getString("marital_status"),
                detailOrBool(rs.getString("smoking_detail"),
                        rs.getObject("smoking", Boolean.class)),
                detailOrBool(rs.getString("drinking_detail"),
                        rs.getObject("drinking", Boolean.class)),
                toUuidArray(rs.getArray("language_ids")),
                toUuidArray(rs.getArray("ethnicity_ids")));
    }

    /** Falls back from the *_detail enum to the boolean flag ('YES'/'NO'). */
    private static String detailOrBool(String detail, Boolean flag) {
        if (detail != null) return detail;
        if (flag == null) return null;
        return flag ? "YES" : "NO";
    }

    private static UUID[] toUuidArray(java.sql.Array arr) throws SQLException {
        if (arr == null) return new UUID[0];
        return (UUID[]) arr.getArray();
    }
}
