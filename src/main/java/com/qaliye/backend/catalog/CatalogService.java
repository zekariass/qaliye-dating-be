package com.qaliye.backend.catalog;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class CatalogService {

    private final NamedParameterJdbcTemplate jdbc;

    public CatalogService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // -------------------------------------------------------------------------
    // Public catalog reads
    // -------------------------------------------------------------------------

    public List<LanguageOption> getActiveLanguages(String countryCode, String q, int limit, int offset) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, code, country_code, name, native_name FROM public.languages WHERE is_active = TRUE\n");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", Math.min(limit, 200))
                .addValue("offset", offset);
        if (countryCode != null && !countryCode.isBlank()) {
            sql.append("  AND country_code = upper(:countryCode)\n");
            params.addValue("countryCode", countryCode.trim());
        }
        if (q != null && !q.isBlank()) {
            sql.append("  AND (lower(name) LIKE :q OR lower(code) LIKE :q)\n");
            params.addValue("q", "%" + q.toLowerCase().trim() + "%");
        }
        sql.append("ORDER BY sort_order ASC, name ASC\nLIMIT :limit OFFSET :offset");
        return jdbc.query(sql.toString(), params, (rs, rowNum) -> mapLanguage(rs));
    }

    public List<EthnicityOption> getActiveEthnicities(String countryCode, String q, int limit, int offset) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, code, country_code, name, region FROM public.ethnicities WHERE is_active = TRUE\n");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", Math.min(limit, 200))
                .addValue("offset", offset);
        if (countryCode != null && !countryCode.isBlank()) {
            sql.append("  AND country_code = upper(:countryCode)\n");
            params.addValue("countryCode", countryCode.trim());
        }
        if (q != null && !q.isBlank()) {
            sql.append("  AND (lower(name) LIKE :q OR lower(code) LIKE :q)\n");
            params.addValue("q", "%" + q.toLowerCase().trim() + "%");
        }
        sql.append("ORDER BY sort_order ASC, name ASC\nLIMIT :limit OFFSET :offset");
        return jdbc.query(sql.toString(), params, (rs, rowNum) -> mapEthnicity(rs));
    }

    // -------------------------------------------------------------------------
    // Expand UUID arrays → objects (used by profile and preferences responses)
    // -------------------------------------------------------------------------

    public List<LanguageOption> expandLanguageIds(UUID[] ids) {
        if (ids == null || ids.length == 0) return Collections.emptyList();
        List<UUID> idList = Arrays.asList(ids);
        return expandLanguageIdList(idList);
    }

    public List<LanguageOption> expandLanguageIdList(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        return jdbc.query("""
                SELECT id, code, country_code, name, native_name
                FROM public.languages
                WHERE id = ANY(:ids::UUID[])
                ORDER BY sort_order ASC, name ASC
                """,
                Map.of("ids", buildUuidArrayParam(ids)),
                (rs, rowNum) -> mapLanguage(rs));
    }

    public List<EthnicityOption> expandEthnicityIds(UUID[] ids) {
        if (ids == null || ids.length == 0) return Collections.emptyList();
        List<UUID> idList = Arrays.asList(ids);
        return expandEthnicityIdList(idList);
    }

    public List<EthnicityOption> expandEthnicityIdList(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        return jdbc.query("""
                SELECT id, code, country_code, name, region
                FROM public.ethnicities
                WHERE id = ANY(:ids::UUID[])
                ORDER BY sort_order ASC, name ASC
                """,
                Map.of("ids", buildUuidArrayParam(ids)),
                (rs, rowNum) -> mapEthnicity(rs));
    }

    // -------------------------------------------------------------------------
    // Validate IDs (for profile/preference writes)
    // -------------------------------------------------------------------------

    public void validateLanguageIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return;
        long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM public.languages
                WHERE id = ANY(:ids::UUID[]) AND is_active = TRUE
                """,
                Map.of("ids", buildUuidArrayParam(ids)), Long.class);
        if (count != ids.size()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "One or more language IDs are unknown or inactive.");
        }
    }

    public void validateEthnicityIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return;
        long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM public.ethnicities
                WHERE id = ANY(:ids::UUID[]) AND is_active = TRUE
                """,
                Map.of("ids", buildUuidArrayParam(ids)), Long.class);
        if (count != ids.size()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "One or more ethnicity IDs are unknown or inactive.");
        }
    }

    // -------------------------------------------------------------------------
    // Resolve preference UUIDs → all matching UUIDs across countries
    // Used for Discovery OR-matching on shared language/ethnicity codes
    // -------------------------------------------------------------------------

    public UUID[] resolveLanguagePreferenceIdsToAllMatching(UUID[] prefIds) {
        if (prefIds == null || prefIds.length == 0) return null;
        List<UUID> result = jdbc.query("""
                SELECT DISTINCT l2.id
                FROM public.languages l1
                JOIN public.languages l2 ON l2.code = l1.code AND l2.is_active = TRUE
                WHERE l1.id = ANY(:ids::UUID[])
                """,
                Map.of("ids", buildUuidArrayParam(Arrays.asList(prefIds))),
                (rs, rowNum) -> rs.getObject("id", UUID.class));
        return result.isEmpty() ? null : result.toArray(UUID[]::new);
    }

    public UUID[] resolveEthnicityPreferenceIdsToAllMatching(UUID[] prefIds) {
        if (prefIds == null || prefIds.length == 0) return null;
        List<UUID> result = jdbc.query("""
                SELECT DISTINCT e2.id
                FROM public.ethnicities e1
                JOIN public.ethnicities e2 ON e2.code = e1.code AND e2.is_active = TRUE
                WHERE e1.id = ANY(:ids::UUID[])
                """,
                Map.of("ids", buildUuidArrayParam(Arrays.asList(prefIds))),
                (rs, rowNum) -> rs.getObject("id", UUID.class));
        return result.isEmpty() ? null : result.toArray(UUID[]::new);
    }

    // -------------------------------------------------------------------------
    // Batch maps for bulk enrichment (used by DiscoveryQueryService)
    // -------------------------------------------------------------------------

    public Map<UUID, LanguageOption> getLanguagesAsMap(Set<UUID> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyMap();
        List<LanguageOption> rows = jdbc.query("""
                SELECT id, code, country_code, name, native_name
                FROM public.languages
                WHERE id = ANY(:ids::UUID[])
                """,
                Map.of("ids", buildUuidArrayParam(ids)),
                (rs, rowNum) -> mapLanguage(rs));
        Map<UUID, LanguageOption> map = new LinkedHashMap<>();
        rows.forEach(l -> map.put(l.id(), l));
        return map;
    }

    public Map<UUID, EthnicityOption> getEthnicitiesAsMap(Set<UUID> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyMap();
        List<EthnicityOption> rows = jdbc.query("""
                SELECT id, code, country_code, name, region
                FROM public.ethnicities
                WHERE id = ANY(:ids::UUID[])
                """,
                Map.of("ids", buildUuidArrayParam(ids)),
                (rs, rowNum) -> mapEthnicity(rs));
        Map<UUID, EthnicityOption> map = new LinkedHashMap<>();
        rows.forEach(e -> map.put(e.id(), e));
        return map;
    }

    // -------------------------------------------------------------------------
    // Admin: Languages
    // -------------------------------------------------------------------------

    @Transactional
    public LanguageOption adminCreateLanguage(String code, String countryCode,
                                               String name, String nativeName,
                                               int sortOrder) {
        validateLanguageCode(code);
        validateCountryCode(countryCode);

        List<UUID> ids = jdbc.query("""
                INSERT INTO public.languages (code, country_code, name, native_name, sort_order)
                VALUES (:code, :countryCode, :name, :nativeName, :sortOrder)
                RETURNING id
                """,
                new MapSqlParameterSource()
                        .addValue("code", code.toLowerCase())
                        .addValue("countryCode", countryCode.toUpperCase())
                        .addValue("name", name)
                        .addValue("nativeName", nativeName)
                        .addValue("sortOrder", sortOrder),
                (rs, rowNum) -> rs.getObject("id", UUID.class));

        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "INSERT_FAILED");
        return getLanguageById(ids.get(0));
    }

    @Transactional
    public LanguageOption adminUpdateLanguage(UUID id, String name, String nativeName,
                                               Boolean isActive, Integer sortOrder) {
        requireLanguageExists(id);
        jdbc.update("""
                UPDATE public.languages SET
                    name         = COALESCE(:name, name),
                    native_name  = COALESCE(:nativeName, native_name),
                    is_active    = COALESCE(:isActive, is_active),
                    sort_order   = COALESCE(:sortOrder, sort_order),
                    updated_at   = NOW()
                WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("name", name)
                        .addValue("nativeName", nativeName)
                        .addValue("isActive", isActive)
                        .addValue("sortOrder", sortOrder));
        return getLanguageById(id);
    }

    @Transactional
    public void adminSoftDeleteLanguage(UUID id) {
        requireLanguageExists(id);
        jdbc.update("UPDATE public.languages SET is_active = FALSE, updated_at = NOW() WHERE id = :id",
                Map.of("id", id));
    }

    public List<LanguageOption> adminListLanguages(String countryCode, int limit, int offset) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, code, country_code, name, native_name FROM public.languages WHERE TRUE\n");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", Math.min(limit, 200))
                .addValue("offset", offset);
        if (countryCode != null && !countryCode.isBlank()) {
            sql.append("  AND country_code = upper(:countryCode)\n");
            params.addValue("countryCode", countryCode.trim());
        }
        sql.append("ORDER BY sort_order ASC, name ASC\nLIMIT :limit OFFSET :offset");
        return jdbc.query(sql.toString(), params, (rs, rowNum) -> mapLanguage(rs));
    }

    // -------------------------------------------------------------------------
    // Admin: Ethnicities
    // -------------------------------------------------------------------------

    @Transactional
    public EthnicityOption adminCreateEthnicity(String code, String countryCode,
                                                  String name, String region,
                                                  int sortOrder) {
        validateEthnicityCode(code);
        validateCountryCode(countryCode);

        List<UUID> ids = jdbc.query("""
                INSERT INTO public.ethnicities (code, country_code, name, region, sort_order)
                VALUES (:code, :countryCode, :name, :region, :sortOrder)
                RETURNING id
                """,
                new MapSqlParameterSource()
                        .addValue("code", code.toLowerCase())
                        .addValue("countryCode", countryCode.toUpperCase())
                        .addValue("name", name)
                        .addValue("region", region)
                        .addValue("sortOrder", sortOrder),
                (rs, rowNum) -> rs.getObject("id", UUID.class));

        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "INSERT_FAILED");
        return getEthnicityById(ids.get(0));
    }

    @Transactional
    public EthnicityOption adminUpdateEthnicity(UUID id, String name, String region,
                                                  Boolean isActive, Integer sortOrder) {
        requireEthnicityExists(id);
        jdbc.update("""
                UPDATE public.ethnicities SET
                    name       = COALESCE(:name, name),
                    region     = COALESCE(:region, region),
                    is_active  = COALESCE(:isActive, is_active),
                    sort_order = COALESCE(:sortOrder, sort_order),
                    updated_at = NOW()
                WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("name", name)
                        .addValue("region", region)
                        .addValue("isActive", isActive)
                        .addValue("sortOrder", sortOrder));
        return getEthnicityById(id);
    }

    @Transactional
    public void adminSoftDeleteEthnicity(UUID id) {
        requireEthnicityExists(id);
        jdbc.update("UPDATE public.ethnicities SET is_active = FALSE, updated_at = NOW() WHERE id = :id",
                Map.of("id", id));
    }

    public List<EthnicityOption> adminListEthnicities(String countryCode, int limit, int offset) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, code, country_code, name, region FROM public.ethnicities WHERE TRUE\n");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", Math.min(limit, 200))
                .addValue("offset", offset);
        if (countryCode != null && !countryCode.isBlank()) {
            sql.append("  AND country_code = upper(:countryCode)\n");
            params.addValue("countryCode", countryCode.trim());
        }
        sql.append("ORDER BY sort_order ASC, name ASC\nLIMIT :limit OFFSET :offset");
        return jdbc.query(sql.toString(), params, (rs, rowNum) -> mapEthnicity(rs));
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private LanguageOption getLanguageById(UUID id) {
        return jdbc.queryForObject("""
                SELECT id, code, country_code, name, native_name FROM public.languages WHERE id = :id
                """, Map.of("id", id), (rs, rowNum) -> mapLanguage(rs));
    }

    private EthnicityOption getEthnicityById(UUID id) {
        return jdbc.queryForObject("""
                SELECT id, code, country_code, name, region FROM public.ethnicities WHERE id = :id
                """, Map.of("id", id), (rs, rowNum) -> mapEthnicity(rs));
    }

    private void requireLanguageExists(UUID id) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM public.languages WHERE id = :id)",
                Map.of("id", id), Boolean.class);
        if (!Boolean.TRUE.equals(exists)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LANGUAGE_NOT_FOUND");
        }
    }

    private void requireEthnicityExists(UUID id) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM public.ethnicities WHERE id = :id)",
                Map.of("id", id), Boolean.class);
        if (!Boolean.TRUE.equals(exists)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ETHNICITY_NOT_FOUND");
        }
    }

    private void validateLanguageCode(String code) {
        if (code == null || code.isBlank() || !code.equals(code.toLowerCase())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Language code must be a non-empty lowercase string.");
        }
    }

    private void validateEthnicityCode(String code) {
        if (code == null || code.isBlank() || !code.equals(code.toLowerCase())
                || !code.matches("[a-z][a-z0-9_-]*")) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Ethnicity code must be a lowercase slug (letters, digits, hyphens, underscores).");
        }
    }

    private void validateCountryCode(String code) {
        if (code == null || code.length() != 2 || !code.matches("[A-Za-z]{2}")) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "country_code must be a valid 2-letter ISO 3166-1 alpha-2 code.");
        }
    }

    private LanguageOption mapLanguage(ResultSet rs) throws SQLException {
        return new LanguageOption(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("country_code"),
                rs.getString("name"),
                rs.getString("native_name")
        );
    }

    private EthnicityOption mapEthnicity(ResultSet rs) throws SQLException {
        return new EthnicityOption(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("country_code"),
                rs.getString("name"),
                rs.getString("region")
        );
    }

    public static String buildUuidArrayParam(Collection<UUID> ids) {
        return ids.stream()
                .map(UUID::toString)
                .collect(Collectors.joining(",", "{", "}"));
    }
}
