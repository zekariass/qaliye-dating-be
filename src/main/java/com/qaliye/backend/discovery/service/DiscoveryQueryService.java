package com.qaliye.backend.discovery.service;

import com.qaliye.backend.activity.ActivityStatus;
import com.qaliye.backend.activity.ActivityStatusService;
import com.qaliye.backend.catalog.CatalogService;
import com.qaliye.backend.catalog.EthnicityOption;
import com.qaliye.backend.catalog.LanguageOption;
import com.qaliye.backend.discovery.config.DiscoveryProperties;
import com.qaliye.backend.discovery.dto.DiscoveryPhotoDto;
import com.qaliye.backend.discovery.dto.DiscoveryProfileDto;
import com.qaliye.backend.discovery.dto.DiscoveryPromptAnswerDto;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class DiscoveryQueryService {

    private final NamedParameterJdbcTemplate jdbc;
    private final StorageSigningService signingService;
    private final DiscoveryProperties props;
    private final ActivityStatusService activityStatusService;
    private final CatalogService catalogService;

    public DiscoveryQueryService(NamedParameterJdbcTemplate jdbc,
                                 StorageSigningService signingService,
                                 DiscoveryProperties props,
                                 ActivityStatusService activityStatusService,
                                 CatalogService catalogService) {
        this.jdbc = jdbc;
        this.signingService = signingService;
        this.props = props;
        this.activityStatusService = activityStatusService;
        this.catalogService = catalogService;
    }

    public record ActorContext(
            UUID addressId,
            String coordsEwkt,
            String interestedInGender,
            int minAge,
            int maxAge,
            int maxDistanceKm,
            boolean showVerifiedOnly,
            String preferredLanguage,
            String locationMode,
            String[] specificCountryCodes,
            boolean expandSearchWhenLimited,
            UUID[] languagePreferenceIds,
            UUID[] ethnicityPreferenceIds,
            String hasChildrenPreference,
            String wantsChildrenPreference,
            String[] religionPreferences
    ) {}

    public record FetchCursor(Double score, UUID userId) {
        public boolean isPresent() {
            return score != null && userId != null;
        }
    }

    private static final String LOAD_ACTOR_CONTEXT_SQL = """
            SELECT au.address_id,
                   ST_AsEWKT(a.coords) AS coords_ewkt,
                   dp.interested_in_gender,
                   dp.min_age,
                   dp.max_age,
                   dp.max_distance_km,
                   dp.show_verified_only,
                   au.preferred_language,
                   dp.location_mode,
                   dp.specific_country_codes,
                   dp.expand_search_when_limited,
                   dp.language_preference_ids,
                   dp.ethnicity_preference_ids,
                   dp.has_children_preference,
                   dp.wants_children_preference,
                   dp.religion_preferences
            FROM app_users au
            JOIN discovery_preferences dp ON dp.user_id = au.id
            JOIN addresses a ON a.id = au.address_id
            WHERE au.id = :actorId
              AND au.status = 'ACTIVE'
              AND au.deleted_at IS NULL
            """;

    private static final String CHECK_ACTOR_PROFILE_SQL = """
            SELECT is_visible, is_onboarded
            FROM profiles
            WHERE user_id = :actorId
            """;

    private static final String CORE_DISCOVERY_SQL = """
            WITH excluded_targets AS (
                SELECT target_user_id AS user_id
                FROM user_discovery_actions
                WHERE actor_user_id = :actorId
                  AND status = 'ACTIVE'
                UNION
                SELECT CASE
                    WHEN user_one_id = :actorId THEN user_two_id
                    ELSE user_one_id
                END AS user_id
                FROM matches
                WHERE (user_one_id = :actorId OR user_two_id = :actorId)
                  AND status = 'ACTIVE'
                UNION
                SELECT CASE
                    WHEN user_one_id = :actorId THEN user_two_id
                    ELSE user_one_id
                END AS user_id
                FROM matches
                WHERE (user_one_id = :actorId OR user_two_id = :actorId)
                  AND status = 'ENDED'
                  AND end_reason IN ('USER_UNMATCH', 'BLOCKED', 'ADMIN_ACTION')
                UNION
                SELECT blocked_user_id AS user_id
                FROM user_blocks
                WHERE blocker_user_id = :actorId AND status = 'ACTIVE'
                UNION
                SELECT blocker_user_id AS user_id
                FROM user_blocks
                WHERE blocked_user_id = :actorId AND status = 'ACTIVE'
            ),
            candidate_distances AS (
                SELECT
                    p.user_id,
                    p.display_name,
                    p.gender,
                    calculate_age(p.date_of_birth)                               AS age,
                    p.bio,
                    p.residency_type,
                    p.is_verified,
                    p.relationship_intention,
                    p.height_cm,
                    p.ethnicity_ids,
                    p.language_ids,
                    p.nationality,
                    p.religion,
                    p.education_level,
                    p.occupation,
                    p.marital_status,
                    p.has_children,
                    p.wants_children,
                    p.smoking,
                    p.drinking,
                    a.city,
                    a.region,
                    a.country_name,
                    GREATEST(
                        1,
                        ROUND(ST_Distance(:actorCoords::geography, a.coords::geography) / 1000.0)::INTEGER
                    )                                                             AS distance_km,
                    au.last_active_at,
                    EXISTS (
                        SELECT 1 FROM active_boosts ab
                        WHERE ab.user_id = p.user_id
                          AND ab.status = 'ACTIVE'
                          AND NOW() BETWEEN ab.started_at AND ab.expires_at
                    )                                                             AS is_boosted,
                    a.coords                                                      AS candidate_coords,
                    (
                        CASE WHEN EXISTS (
                            SELECT 1 FROM active_boosts ab
                            WHERE ab.user_id = p.user_id
                              AND ab.status = 'ACTIVE'
                              AND NOW() BETWEEN ab.started_at AND ab.expires_at
                        ) THEN 1000.0 ELSE 0.0 END
                        + (EXTRACT(EPOCH FROM au.last_active_at) / 1e9)
                        + CASE
                            WHEN :locationMode = 'nearby' AND NOT :skipDistance
                            THEN (1.0 - LEAST(GREATEST(
                                1,
                                ROUND(ST_Distance(:actorCoords::geography, a.coords::geography) / 1000.0)::INTEGER
                            )::float / :maxDistanceKm, 1.0)) * 200.0
                            ELSE 0.0
                          END
                    )                                                             AS discovery_score
                FROM profiles p
                JOIN app_users au    ON au.id = p.user_id
                JOIN addresses a     ON a.id  = au.address_id
                WHERE p.is_visible      = TRUE
                  AND p.is_onboarded    = TRUE
                  AND au.status         = 'ACTIVE'
                  AND au.deleted_at     IS NULL
                  AND p.user_id        <> :actorId
                  AND p.discovery_mode <> 'INCOGNITO'
                  AND p.gender          = :targetGender
                  AND p.user_id        NOT IN (SELECT user_id FROM excluded_targets)
                  AND calculate_age(p.date_of_birth) BETWEEN :minAge AND :maxAge
                  AND (:showVerifiedOnly = FALSE OR p.is_verified = TRUE)
                  AND p.residency_type  = ANY(:residencyTypes::TEXT[])
                  AND (:langPrefIds = '{}' OR p.language_ids && :langPrefIds::UUID[])
                  AND (:ethPrefIds  = '{}' OR p.ethnicity_ids && :ethPrefIds::UUID[])
                  AND (:hasChildrenPref = 'any' OR
                       (:hasChildrenPref = 'yes' AND p.has_children = TRUE) OR
                       (:hasChildrenPref = 'no'  AND p.has_children IS DISTINCT FROM TRUE))
                  AND (:wantsChildrenPref = 'any' OR
                       (:wantsChildrenPref = 'yes' AND p.wants_children = TRUE) OR
                       (:wantsChildrenPref = 'no'  AND p.wants_children IS DISTINCT FROM TRUE) OR
                       (:wantsChildrenPref = 'not_sure' AND p.wants_children IS NULL) OR
                       (:wantsChildrenPref = 'open_to_discussion' AND p.wants_children IS DISTINCT FROM FALSE))
                  AND (:religionPrefs = '{}' OR p.religion = ANY(:religionPrefs::TEXT[]))
                  AND (:specificCountryCodes = '{}' OR a.country_code = ANY(:specificCountryCodes::TEXT[]))
                  AND EXISTS (
                      SELECT 1 FROM profile_photos pp
                      WHERE pp.user_id            = p.user_id
                        AND pp.is_primary         = TRUE
                        AND pp.moderation_status  = 'APPROVED'
                        AND pp.deleted_at         IS NULL
                  )
            )
            SELECT
                cd.*,
                au.show_activity_status
            FROM candidate_distances cd
            JOIN app_users au ON au.id = cd.user_id
            WHERE (
                :skipDistance
                OR :locationMode <> 'nearby'
                OR ST_DWithin(
                    :actorCoords::geography,
                    cd.candidate_coords::geography,
                    :maxDistanceKm * 1000.0
                )
            )
              AND (
                  :noCursor
                  OR cd.discovery_score < :cursorScore
                  OR (cd.discovery_score = :cursorScore AND cd.user_id > :cursorUserId::uuid)
              )
            ORDER BY cd.discovery_score DESC, cd.user_id ASC
            LIMIT :limit
            """;

    private static final String COUNT_DISCOVERY_SQL = """
            WITH excluded_targets AS (
                SELECT target_user_id AS user_id
                FROM user_discovery_actions
                WHERE actor_user_id = :actorId
                  AND status = 'ACTIVE'
                UNION
                SELECT CASE
                    WHEN user_one_id = :actorId THEN user_two_id
                    ELSE user_one_id
                END AS user_id
                FROM matches
                WHERE (user_one_id = :actorId OR user_two_id = :actorId)
                  AND status = 'ACTIVE'
                UNION
                SELECT CASE
                    WHEN user_one_id = :actorId THEN user_two_id
                    ELSE user_one_id
                END AS user_id
                FROM matches
                WHERE (user_one_id = :actorId OR user_two_id = :actorId)
                  AND status = 'ENDED'
                  AND end_reason IN ('USER_UNMATCH', 'BLOCKED', 'ADMIN_ACTION')
                UNION
                SELECT blocked_user_id AS user_id
                FROM user_blocks
                WHERE blocker_user_id = :actorId AND status = 'ACTIVE'
                UNION
                SELECT blocker_user_id AS user_id
                FROM user_blocks
                WHERE blocked_user_id = :actorId AND status = 'ACTIVE'
            )
            SELECT COUNT(*)
            FROM profiles p
            JOIN app_users au    ON au.id = p.user_id
            JOIN addresses a     ON a.id  = au.address_id
            WHERE p.is_visible      = TRUE
              AND p.is_onboarded    = TRUE
              AND au.status         = 'ACTIVE'
              AND au.deleted_at     IS NULL
              AND p.user_id        <> :actorId
              AND p.gender          = :targetGender
              AND p.user_id        NOT IN (SELECT user_id FROM excluded_targets)
              AND calculate_age(p.date_of_birth) BETWEEN :minAge AND :maxAge
              AND (:showVerifiedOnly = FALSE OR p.is_verified = TRUE)
              AND p.residency_type  = ANY(:residencyTypes::TEXT[])
              AND (:langPrefIds = '{}' OR p.language_ids && :langPrefIds::UUID[])
              AND (:ethPrefIds  = '{}' OR p.ethnicity_ids && :ethPrefIds::UUID[])
              AND (:hasChildrenPref = 'any' OR
                   (:hasChildrenPref = 'yes' AND p.has_children = TRUE) OR
                   (:hasChildrenPref = 'no'  AND p.has_children IS DISTINCT FROM TRUE))
              AND (:wantsChildrenPref = 'any' OR
                   (:wantsChildrenPref = 'yes' AND p.wants_children = TRUE) OR
                   (:wantsChildrenPref = 'no'  AND p.wants_children IS DISTINCT FROM TRUE) OR
                   (:wantsChildrenPref = 'not_sure' AND p.wants_children IS NULL) OR
                   (:wantsChildrenPref = 'open_to_discussion' AND p.wants_children IS DISTINCT FROM FALSE))
              AND (:religionPrefs = '{}' OR p.religion = ANY(:religionPrefs::TEXT[]))
              AND (:specificCountryCodes = '{}' OR a.country_code = ANY(:specificCountryCodes::TEXT[]))
              AND EXISTS (
                  SELECT 1 FROM profile_photos pp
                  WHERE pp.user_id            = p.user_id
                    AND pp.is_primary         = TRUE
                    AND pp.moderation_status  = 'APPROVED'
                    AND pp.deleted_at         IS NULL
              )
              AND (
                  :skipDistance
                  OR :locationMode <> 'nearby'
                  OR ST_DWithin(
                      :actorCoords::geography,
                      a.coords::geography,
                      :maxDistanceKm * 1000.0
                  )
              )
            """;

    private static final String BATCH_PHOTOS_SQL = """
            SELECT pp.id, pp.user_id, pp.storage_bucket, pp.storage_path, pp.photo_order, pp.is_primary
            FROM profile_photos pp
            WHERE pp.user_id          = ANY(:candidateIds::UUID[])
              AND pp.moderation_status = 'APPROVED'
              AND pp.deleted_at        IS NULL
            ORDER BY pp.user_id, pp.photo_order ASC
            """;

    private static final String BATCH_PROMPTS_SQL = """
            SELECT ppa.user_id,
                   ppa.prompt_id,
                   COALESCE(ppt.prompt_text, pp.prompt_text) AS prompt_text,
                   ppa.answer_text
            FROM profile_prompt_answers ppa
            JOIN profile_prompts pp
                ON pp.id = ppa.prompt_id AND pp.is_active = TRUE
            LEFT JOIN profile_prompt_translations ppt
                ON ppt.prompt_id = ppa.prompt_id
               AND ppt.locale    = :locale
            WHERE ppa.user_id = ANY(:candidateIds::UUID[])
            ORDER BY ppa.user_id, pp.display_order ASC
            """;

    public ActorContext loadActorContext(UUID actorId) {
        var params = new MapSqlParameterSource("actorId", actorId);
        return jdbc.query(LOAD_ACTOR_CONTEXT_SQL, params, rs -> {
            if (!rs.next()) return null;
            UUID addressId = rs.getObject("address_id", UUID.class);
            if (addressId == null) return null;

            String locationMode = rs.getString("location_mode");
            if (locationMode == null) locationMode = "anywhere";

            java.sql.Array ccArr = rs.getArray("specific_country_codes");
            String[] specificCountryCodes = ccArr != null ? (String[]) ccArr.getArray() : new String[0];

            java.sql.Array langArr = rs.getArray("language_preference_ids");
            UUID[] langPrefIds = langArr != null ? toUuidArray((Object[]) langArr.getArray()) : new UUID[0];

            java.sql.Array ethArr = rs.getArray("ethnicity_preference_ids");
            UUID[] ethPrefIds = ethArr != null ? toUuidArray((Object[]) ethArr.getArray()) : new UUID[0];

            java.sql.Array relArr = rs.getArray("religion_preferences");
            String[] religionPrefs = relArr != null ? (String[]) relArr.getArray() : new String[0];

            UUID[] resolvedLangIds = catalogService.resolveLanguagePreferenceIdsToAllMatching(langPrefIds);
            UUID[] resolvedEthIds = catalogService.resolveEthnicityPreferenceIdsToAllMatching(ethPrefIds);

            return new ActorContext(
                    addressId,
                    rs.getString("coords_ewkt"),
                    rs.getString("interested_in_gender"),
                    rs.getInt("min_age"),
                    rs.getInt("max_age"),
                    rs.getInt("max_distance_km"),
                    rs.getBoolean("show_verified_only"),
                    rs.getString("preferred_language"),
                    locationMode,
                    specificCountryCodes,
                    rs.getBoolean("expand_search_when_limited"),
                    resolvedLangIds != null ? resolvedLangIds : new UUID[0],
                    resolvedEthIds != null ? resolvedEthIds : new UUID[0],
                    rs.getString("has_children_preference"),
                    rs.getString("wants_children_preference"),
                    religionPrefs
            );
        });
    }

    private static UUID[] toUuidArray(Object[] raw) {
        if (raw == null) return new UUID[0];
        UUID[] result = new UUID[raw.length];
        for (int i = 0; i < raw.length; i++) {
            result[i] = raw[i] instanceof UUID u ? u : UUID.fromString(raw[i].toString());
        }
        return result;
    }

    private static final String CHECK_ACTOR_ACCOUNT_SQL = """
            SELECT status, deleted_at, address_id
            FROM app_users
            WHERE id = :actorId
            """;

    private static final String CHECK_ACTOR_PREFS_SQL = """
            SELECT 1 FROM discovery_preferences WHERE user_id = :actorId
            """;

    public boolean isActorProfileEligible(UUID actorId) {
        var params = new MapSqlParameterSource("actorId", actorId);
        return Boolean.TRUE.equals(jdbc.query(CHECK_ACTOR_PROFILE_SQL, params, rs -> {
            if (!rs.next()) return false;
            return rs.getBoolean("is_visible") && rs.getBoolean("is_onboarded");
        }));
    }

    public enum ActorEligibilityResult { ELIGIBLE, ACCOUNT_INELIGIBLE, PROFILE_INCOMPLETE }

    public ActorEligibilityResult checkActorEligibilityReason(UUID actorId) {
        var params = new MapSqlParameterSource("actorId", actorId);
        Boolean accountOk = jdbc.query(CHECK_ACTOR_ACCOUNT_SQL, params, rs -> {
            if (!rs.next()) return false;
            boolean active = "ACTIVE".equals(rs.getString("status"));
            boolean notDeleted = rs.getObject("deleted_at") == null;
            return active && notDeleted;
        });
        if (!Boolean.TRUE.equals(accountOk)) return ActorEligibilityResult.ACCOUNT_INELIGIBLE;

        if (!isActorProfileEligible(actorId)) return ActorEligibilityResult.ACCOUNT_INELIGIBLE;

        boolean hasAddress = Boolean.TRUE.equals(jdbc.query(CHECK_ACTOR_ACCOUNT_SQL, params, rs -> {
            if (!rs.next()) return false;
            return rs.getObject("address_id") != null;
        }));
        if (!hasAddress) return ActorEligibilityResult.PROFILE_INCOMPLETE;

        boolean hasPrefs = !jdbc.queryForList(CHECK_ACTOR_PREFS_SQL, params).isEmpty();
        if (!hasPrefs) return ActorEligibilityResult.PROFILE_INCOMPLETE;

        return ActorEligibilityResult.ELIGIBLE;
    }

    public List<DiscoveryProfileDto> fetchProfiles(UUID actorId, ActorContext ctx,
                                                    int limit,
                                                    FetchCursor cursor, boolean skipDistance) {
        String[] residencyTypes = resolveResidencyTypes(ctx);
        String residencyParam = buildArrayParam(residencyTypes);

        var params = buildCoreParams(actorId, ctx, residencyParam, limit, cursor, skipDistance);
        Instant now = activityStatusService.now();

        Map<UUID, UUID[]> rawLangIds = new LinkedHashMap<>();
        Map<UUID, UUID[]> rawEthIds  = new LinkedHashMap<>();

        List<DiscoveryProfileDto> profiles = new ArrayList<>(
                jdbc.query(CORE_DISCOVERY_SQL, params,
                        (rs, rowNum) -> mapProfile(rs, rowNum, now, rawLangIds, rawEthIds)));
        if (profiles.isEmpty()) return profiles;

        enrichWithCatalogData(profiles, rawLangIds, rawEthIds);
        enrichWithPhotos(profiles);
        enrichWithPrompts(profiles, ctx.preferredLanguage());
        return profiles;
    }

    public int countEligible(UUID actorId, ActorContext ctx, boolean skipDistance) {
        String[] residencyTypes = resolveResidencyTypes(ctx);
        String residencyParam = buildArrayParam(residencyTypes);

        var params = buildCoreParams(actorId, ctx, residencyParam, 0,
                new FetchCursor(null, null), skipDistance);
        Integer count = jdbc.queryForObject(COUNT_DISCOVERY_SQL, params, Integer.class);
        return count != null ? count : 0;
    }

    public DiscoveryProfileDto fetchSingleProfile(UUID actorId, UUID targetUserId,
                                                   ActorContext ctx) {
        String residencyParam = buildArrayParam(resolveResidencyTypes(ctx));
        var params = buildCoreParams(actorId, ctx, residencyParam, 1,
                new FetchCursor(null, null), false);
        params.addValue("targetUserId", targetUserId);
        Instant now = activityStatusService.now();

        String singleProfileSql = CORE_DISCOVERY_SQL.replace(
                "AND p.user_id        NOT IN (SELECT user_id FROM excluded_targets)",
                "AND p.user_id        NOT IN (SELECT user_id FROM excluded_targets)\n"
                        + "                  AND p.user_id        = :targetUserId");

        Map<UUID, UUID[]> rawLangIds = new LinkedHashMap<>();
        Map<UUID, UUID[]> rawEthIds  = new LinkedHashMap<>();

        List<DiscoveryProfileDto> results = new ArrayList<>(
                jdbc.query(singleProfileSql, params,
                        (rs, rowNum) -> mapProfile(rs, rowNum, now, rawLangIds, rawEthIds)));
        if (results.isEmpty()) return null;

        enrichWithCatalogData(results, rawLangIds, rawEthIds);
        enrichWithPhotos(results);
        enrichWithPrompts(results, ctx.preferredLanguage());
        return results.get(0);
    }

    private MapSqlParameterSource buildCoreParams(UUID actorId, ActorContext ctx,
                                                   String residencyParam,
                                                   int limit, FetchCursor cursor,
                                                   boolean skipDistance) {
        String langPref = buildUuidArrayParam(Arrays.asList(ctx.languagePreferenceIds()));
        String ethPref  = buildUuidArrayParam(Arrays.asList(ctx.ethnicityPreferenceIds()));
        boolean noCursor = cursor == null || !cursor.isPresent();
        return new MapSqlParameterSource()
                .addValue("actorId", actorId)
                .addValue("skipDistance", skipDistance)
                .addValue("actorCoords", ctx.coordsEwkt())
                .addValue("targetGender", ctx.interestedInGender())
                .addValue("minAge", ctx.minAge())
                .addValue("maxAge", ctx.maxAge() > 0 ? ctx.maxAge() : 120)
                .addValue("maxDistanceKm", ctx.maxDistanceKm() > 0 ? ctx.maxDistanceKm() : 500)
                .addValue("residencyTypes", residencyParam)
                .addValue("showVerifiedOnly", ctx.showVerifiedOnly())
                .addValue("locationMode", ctx.locationMode() != null ? ctx.locationMode() : "nearby")
                .addValue("langPrefIds", langPref)
                .addValue("ethPrefIds", ethPref)
                .addValue("hasChildrenPref", ctx.hasChildrenPreference() != null ? ctx.hasChildrenPreference() : "any")
                .addValue("wantsChildrenPref", ctx.wantsChildrenPreference() != null ? ctx.wantsChildrenPreference() : "any")
                .addValue("religionPrefs", buildArrayParam(ctx.religionPreferences() != null ? ctx.religionPreferences() : new String[0]))
                .addValue("specificCountryCodes", buildArrayParam(ctx.specificCountryCodes() != null ? ctx.specificCountryCodes() : new String[0]))
                .addValue("limit", limit)
                .addValue("noCursor", noCursor)
                .addValue("cursorScore", noCursor ? 0.0 : cursor.score())
                .addValue("cursorUserId", noCursor ? null : cursor.userId().toString());
    }

    private void enrichWithPhotos(List<DiscoveryProfileDto> profiles) {
        List<UUID> ids = profiles.stream().map(DiscoveryProfileDto::userId).toList();
        String idsParam = buildUuidArrayParam(ids);
        var params = new MapSqlParameterSource("candidateIds", idsParam);

        Map<UUID, List<DiscoveryPhotoDto>> photoMap = new LinkedHashMap<>();
        jdbc.query(BATCH_PHOTOS_SQL, params, rs -> {
            UUID userId = rs.getObject("user_id", UUID.class);
            UUID photoId = rs.getObject("id", UUID.class);
            int order = rs.getInt("photo_order");
            boolean isPrimary = rs.getBoolean("is_primary");
            String bucket = rs.getString("storage_bucket");
            String path = rs.getString("storage_path");

            DiscoveryPhotoDto photo = signingService.signPhoto(photoId, order, isPrimary, bucket, path);
            photoMap.computeIfAbsent(userId, k -> new ArrayList<>()).add(photo);
        });

        for (int i = 0; i < profiles.size(); i++) {
            DiscoveryProfileDto p = profiles.get(i);
            profiles.set(i, p.withPhotos(photoMap.getOrDefault(p.userId(), Collections.emptyList())));
        }
    }

    private void enrichWithPrompts(List<DiscoveryProfileDto> profiles, String locale) {
        List<UUID> ids = profiles.stream().map(DiscoveryProfileDto::userId).toList();
        String idsParam = buildUuidArrayParam(ids);
        var params = new MapSqlParameterSource()
                .addValue("candidateIds", idsParam)
                .addValue("locale", locale);

        Map<UUID, List<DiscoveryPromptAnswerDto>> promptMap = new LinkedHashMap<>();
        jdbc.query(BATCH_PROMPTS_SQL, params, rs -> {
            UUID userId = rs.getObject("user_id", UUID.class);
            UUID promptId = rs.getObject("prompt_id", UUID.class);
            String promptText = rs.getString("prompt_text");
            String answerText = rs.getString("answer_text");
            promptMap.computeIfAbsent(userId, k -> new ArrayList<>())
                    .add(new DiscoveryPromptAnswerDto(promptId, promptText, answerText));
        });

        for (int i = 0; i < profiles.size(); i++) {
            DiscoveryProfileDto p = profiles.get(i);
            profiles.set(i, p.withPromptAnswers(promptMap.getOrDefault(p.userId(), Collections.emptyList())));
        }
    }

    private static final String[] ALL_RESIDENCY_TYPES = {"ETHIOPIA", "ERITREA", "DIASPORA"};

    private static String[] resolveResidencyTypes(ActorContext ctx) {
        return resolveFromLocationMode(ctx);
    }

    private static String[] resolveFromLocationMode(ActorContext ctx) {
        return switch (ctx.locationMode()) {
            case "diaspora" -> new String[]{"DIASPORA"};
            case "specific_countries" -> ALL_RESIDENCY_TYPES;
            default -> ALL_RESIDENCY_TYPES;
        };
    }

    private static String buildArrayParam(String[] values) {
        return "{" + String.join(",", values) + "}";
    }

    private static String buildUuidArrayParam(List<UUID> ids) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(ids.get(i));
        }
        sb.append("}");
        return sb.toString();
    }

    private DiscoveryProfileDto mapProfile(ResultSet rs, int rowNum, Instant now,
                                             Map<UUID, UUID[]> rawLangIds,
                                             Map<UUID, UUID[]> rawEthIds) throws SQLException {
        OffsetDateTime lastActiveAt = rs.getObject("last_active_at", OffsetDateTime.class);
        boolean showActivity = rs.getBoolean("show_activity_status");
        ActivityStatus activityStatus = activityStatusService.resolve(showActivity, lastActiveAt, now);
        UUID userId = rs.getObject("user_id", UUID.class);

        java.sql.Array langArr = rs.getArray("language_ids");
        java.sql.Array ethArr  = rs.getArray("ethnicity_ids");
        if (langArr != null) rawLangIds.put(userId, toUuidArray((Object[]) langArr.getArray()));
        if (ethArr  != null) rawEthIds.put(userId,  toUuidArray((Object[]) ethArr.getArray()));

        return new DiscoveryProfileDto(
                userId,
                rs.getString("display_name"),
                rs.getInt("age"),
                rs.getString("gender"),
                rs.getString("bio"),
                rs.getString("residency_type"),
                rs.getString("city"),
                rs.getString("region"),
                rs.getString("country_name"),
                rs.getObject("distance_km") != null ? rs.getInt("distance_km") : null,
                rs.getBoolean("is_verified"),
                rs.getString("relationship_intention"),
                rs.getObject("height_cm") != null ? rs.getInt("height_cm") : null,
                Collections.emptyList(),
                rs.getString("nationality"),
                rs.getString("religion"),
                rs.getString("education_level"),
                rs.getString("occupation"),
                rs.getString("marital_status"),
                rs.getObject("has_children") != null ? rs.getBoolean("has_children") : null,
                rs.getObject("wants_children") != null ? rs.getBoolean("wants_children") : null,
                rs.getString("smoking"),
                rs.getString("drinking"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                rs.getBoolean("is_boosted"),
                rs.getDouble("discovery_score"),
                activityStatus
        );
    }

    private void enrichWithCatalogData(List<DiscoveryProfileDto> profiles,
                                        Map<UUID, UUID[]> rawLangIds,
                                        Map<UUID, UUID[]> rawEthIds) {
        Set<UUID> allLangIds = new HashSet<>();
        Set<UUID> allEthIds  = new HashSet<>();
        rawLangIds.values().forEach(arr -> Collections.addAll(allLangIds, arr));
        rawEthIds.values().forEach(arr -> Collections.addAll(allEthIds, arr));

        Map<UUID, LanguageOption>  langMap = catalogService.getLanguagesAsMap(allLangIds);
        Map<UUID, EthnicityOption> ethMap  = catalogService.getEthnicitiesAsMap(allEthIds);

        for (int i = 0; i < profiles.size(); i++) {
            DiscoveryProfileDto p = profiles.get(i);
            UUID uid = p.userId();

            List<LanguageOption> langs = rawLangIds.containsKey(uid)
                    ? Arrays.stream(rawLangIds.get(uid)).map(langMap::get)
                             .filter(Objects::nonNull).toList()
                    : Collections.emptyList();
            List<EthnicityOption> eths = rawEthIds.containsKey(uid)
                    ? Arrays.stream(rawEthIds.get(uid)).map(ethMap::get)
                             .filter(Objects::nonNull).toList()
                    : Collections.emptyList();

            profiles.set(i, p.withCatalogData(eths, langs));
        }
    }
}
