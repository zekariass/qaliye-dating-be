package com.qaliye.backend.location;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LocationService {

    private final NamedParameterJdbcTemplate jdbc;

    public LocationService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> search(String query, String countryCode, int limit) {
        String pattern = "%" + query.trim() + "%";
        Map<String, Object> params = new HashMap<>();
        params.put("pattern", pattern);
        params.put("limit", Math.min(limit, 25));

        String sql = """
                SELECT id, city, region, country_code, country_name, display_name, location_precision
                FROM location_places
                WHERE is_active = TRUE
                  AND (city ILIKE :pattern
                    OR region ILIKE :pattern
                    OR country_name ILIKE :pattern
                    OR display_name ILIKE :pattern
                    OR alternative_names ILIKE :pattern)
                """ +
                (countryCode != null && !countryCode.isBlank() ? " AND country_code = :countryCode\n" : "") +
                " ORDER BY city LIMIT :limit";

        if (countryCode != null && !countryCode.isBlank()) {
            params.put("countryCode", countryCode.toUpperCase());
        }

        return jdbc.query(sql, params, (rs, row) -> {
            Map<String, Object> place = new LinkedHashMap<>();
            place.put("place_id", rs.getObject("id").toString());
            place.put("display_name", rs.getString("display_name"));
            place.put("city", rs.getString("city"));
            place.put("region", rs.getString("region"));
            place.put("country_code", rs.getString("country_code"));
            place.put("country_name", rs.getString("country_name"));
            place.put("location_precision", rs.getString("location_precision"));
            return place;
        });
    }
}
