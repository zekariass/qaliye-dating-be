package com.qaliye.backend.discovery.service;

import com.qaliye.backend.discovery.config.DiscoveryProperties;
import com.qaliye.backend.discovery.dto.DiscoveryPhotoDto;
import com.qaliye.backend.discovery.dto.DiscoveryProfileDto;
import com.qaliye.backend.discovery.dto.DiscoveryPromptAnswerDto;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@Service
public class DiscoveryQueryService {

    private final NamedParameterJdbcTemplate jdbc;
    private final StorageSigningService signingService;
    private final DiscoveryProperties props;

    public DiscoveryQueryService(NamedParameterJdbcTemplate jdbc,
                                 StorageSigningService signingService,
                                 DiscoveryProperties props) {
        this.jdbc = jdbc;
        this.signingService = signingService;
        this.props = props;
    }

    public record ActorContext(
            UUID addressId,
            String coordsEwkt,
            String discoveryMode,
            String interestedInGender,
            int minAge,
            int maxAge,
            int maxDistanceKm,
            String[] preferredResidencyTypes,
            boolean showVerifiedOnly,
            boolean openToLongDistance,
            String preferredLanguage
    ) {}

    private static final String LOAD_ACTOR_CONTEXT_SQL = """
            SELECT au.address_id,
                   ST_AsEWKT(a.coords) AS coords_ewkt,
                   dp.discovery_mode,
                   dp.interested_in_gender,
                   dp.min_age,
                   dp.max_age,
                   dp.max_distance_km,
                   dp.preferred_residency_types,
                   dp.show_verified_only,
                   dp.open_to_long_distance,
                   au.preferred_language
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
                    p.ethnicity,
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
                    CASE
                        WHEN :locationFilter = 'NEARBY' THEN
                            GREATEST(
                                1,
                                ROUND(ST_Distance(:actorCoords::geography, a.coords::geography) / 1000.0)::INTEGER
                            )
                        ELSE NULL
                    END                                                           AS distance_km,
                    EXISTS (
                        SELECT 1 FROM active_boosts ab
                        WHERE ab.user_id = p.user_id
                          AND NOW() BETWEEN ab.started_at AND ab.expires_at
                    )                                                             AS is_boosted
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
                (
                    CASE WHEN cd.is_boosted THEN 1000.0 ELSE 0.0 END
                    + (EXTRACT(EPOCH FROM au.last_active_at) / 1e9)
                    + CASE
                        WHEN :locationFilter = 'NEARBY' AND cd.distance_km IS NOT NULL
                        THEN (1.0 - LEAST(cd.distance_km::float / :maxDistanceKm, 1.0)) * 200.0
                        ELSE 0.0
                      END
                )                                                                AS discovery_score
            FROM candidate_distances cd
            JOIN app_users au ON au.id = cd.user_id
            WHERE (
                :locationFilter <> 'NEARBY'
                OR :openToLongDistance = TRUE
                OR (
                    ST_DWithin(
                        :actorCoords::geography,
                        (SELECT coords FROM addresses WHERE id = (SELECT address_id FROM app_users WHERE id = cd.user_id)),
                        :maxDistanceKm * 1000.0
                    )
                )
            )
            ORDER BY discovery_score DESC, cd.user_id ASC
            LIMIT :limit
            OFFSET :offset
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
              AND EXISTS (
                  SELECT 1 FROM profile_photos pp
                  WHERE pp.user_id            = p.user_id
                    AND pp.is_primary         = TRUE
                    AND pp.moderation_status  = 'APPROVED'
                    AND pp.deleted_at         IS NULL
              )
              AND (
                  :locationFilter <> 'NEARBY'
                  OR :openToLongDistance = TRUE
                  OR (
                      ST_DWithin(
                          :actorCoords::geography,
                          a.coords::geography,
                          :maxDistanceKm * 1000.0
                      )
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
            String[] residencyTypes = (String[]) rs.getArray("preferred_residency_types").getArray();
            return new ActorContext(
                    addressId,
                    rs.getString("coords_ewkt"),
                    rs.getString("discovery_mode"),
                    rs.getString("interested_in_gender"),
                    rs.getInt("min_age"),
                    rs.getInt("max_age"),
                    rs.getInt("max_distance_km"),
                    residencyTypes,
                    rs.getBoolean("show_verified_only"),
                    rs.getBoolean("open_to_long_distance"),
                    rs.getString("preferred_language")
            );
        });
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
                                                    String locationFilter, int limit, int offset) {
        String[] residencyTypes = resolveResidencyTypes(locationFilter, ctx);
        String residencyParam = buildArrayParam(residencyTypes);

        var params = buildCoreParams(actorId, ctx, locationFilter, residencyParam, limit, offset);

        List<DiscoveryProfileDto> profiles = new ArrayList<>(
                jdbc.query(CORE_DISCOVERY_SQL, params, this::mapProfile));
        if (profiles.isEmpty()) return profiles;

        enrichWithPhotos(profiles);
        enrichWithPrompts(profiles, ctx.preferredLanguage());
        return profiles;
    }

    public int countEligible(UUID actorId, ActorContext ctx, String locationFilter) {
        String[] residencyTypes = resolveResidencyTypes(locationFilter, ctx);
        String residencyParam = buildArrayParam(residencyTypes);

        var params = buildCoreParams(actorId, ctx, locationFilter, residencyParam, 0, 0);
        Integer count = jdbc.queryForObject(COUNT_DISCOVERY_SQL, params, Integer.class);
        return count != null ? count : 0;
    }

    public DiscoveryProfileDto fetchSingleProfile(UUID actorId, UUID targetUserId,
                                                   ActorContext ctx, String locationFilter) {
        String residencyParam = buildArrayParam(resolveResidencyTypes(locationFilter, ctx));
        var params = buildCoreParams(actorId, ctx, locationFilter, residencyParam, 1, 0);
        params.addValue("targetUserId", targetUserId);

        String singleProfileSql = CORE_DISCOVERY_SQL.replace(
                "AND p.user_id        NOT IN (SELECT user_id FROM excluded_targets)",
                "AND p.user_id        NOT IN (SELECT user_id FROM excluded_targets)\n"
                        + "                  AND p.user_id        = :targetUserId");

        List<DiscoveryProfileDto> results = new ArrayList<>(
                jdbc.query(singleProfileSql, params, this::mapProfile));
        if (results.isEmpty()) return null;

        enrichWithPhotos(results);
        enrichWithPrompts(results, ctx.preferredLanguage());
        return results.get(0);
    }

    private MapSqlParameterSource buildCoreParams(UUID actorId, ActorContext ctx,
                                                   String locationFilter, String residencyParam,
                                                   int limit, int offset) {
        return new MapSqlParameterSource()
                .addValue("actorId", actorId)
                .addValue("locationFilter", locationFilter)
                .addValue("actorCoords", ctx.coordsEwkt())
                .addValue("targetGender", ctx.interestedInGender())
                .addValue("minAge", ctx.minAge())
                .addValue("maxAge", ctx.maxAge())
                .addValue("maxDistanceKm", ctx.maxDistanceKm())
                .addValue("residencyTypes", residencyParam)
                .addValue("showVerifiedOnly", ctx.showVerifiedOnly())
                .addValue("openToLongDistance", ctx.openToLongDistance())
                .addValue("limit", limit)
                .addValue("offset", offset);
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

    private static String[] resolveResidencyTypes(String locationFilter, ActorContext ctx) {
        return switch (locationFilter) {
            case "ETHIOPIA" -> new String[]{"ETHIOPIA"};
            case "ERITREA" -> new String[]{"ERITREA"};
            case "DIASPORA" -> new String[]{"DIASPORA"};
            default -> ctx.preferredResidencyTypes();
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

    private DiscoveryProfileDto mapProfile(ResultSet rs, int rowNum) throws SQLException {
        return new DiscoveryProfileDto(
                rs.getObject("user_id", UUID.class),
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
                rs.getString("ethnicity"),
                rs.getString("nationality"),
                rs.getString("religion"),
                rs.getString("education_level"),
                rs.getString("occupation"),
                rs.getString("marital_status"),
                rs.getBoolean("has_children"),
                rs.getObject("wants_children") != null ? rs.getBoolean("wants_children") : null,
                rs.getBoolean("smoking"),
                rs.getBoolean("drinking"),
                Collections.emptyList(),
                Collections.emptyList(),
                rs.getBoolean("is_boosted"),
                rs.getDouble("discovery_score")
        );
    }
}
