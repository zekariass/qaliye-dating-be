package com.qaliye.backend.billing.service;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class CountrySettingsService {

    private final NamedParameterJdbcTemplate jdbc;

    public CountrySettingsService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record CountrySettings(
            String countryCode,
            boolean subscriptionEnabled,
            boolean creditsEnabled,
            boolean identityVerificationRequired
    ) {}

    private static final String FIND_SETTINGS_SQL = """
            SELECT country_code, subscription_enabled, credits_enabled, identity_verification_required
            FROM country_settings
            WHERE country_code = :countryCode
            """;

    private static final String GET_USER_COUNTRY_SQL = """
            SELECT COALESCE(au.billing_country_code, a.country_code, 'GLOBAL') AS country_code
            FROM app_users au
            LEFT JOIN addresses a ON a.id = au.address_id
            WHERE au.id = :userId
            """;

    public CountrySettings getSettings(String countryCode) {
        var params = new MapSqlParameterSource("countryCode", countryCode);
        var results = jdbc.query(FIND_SETTINGS_SQL, params, (rs, rn) -> new CountrySettings(
                rs.getString("country_code"),
                rs.getBoolean("subscription_enabled"),
                rs.getBoolean("credits_enabled"),
                rs.getBoolean("identity_verification_required")
        ));
        if (!results.isEmpty()) return results.get(0);

        if (!"GLOBAL".equals(countryCode)) {
            return getSettings("GLOBAL");
        }
        return new CountrySettings("GLOBAL", true, true, false);
    }

    public CountrySettings getSettingsForUser(UUID userId) {
        var results = jdbc.queryForList(GET_USER_COUNTRY_SQL,
                new MapSqlParameterSource("userId", userId));
        String countryCode = results.isEmpty() ? "GLOBAL"
                : (String) results.get(0).getOrDefault("country_code", "GLOBAL");
        if (countryCode == null) countryCode = "GLOBAL";
        return getSettings(countryCode);
    }
}
