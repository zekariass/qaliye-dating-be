package com.qaliye.backend.discovery;

import com.qaliye.backend.storage.SupabaseStorageService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class DiscoveryService {

    public record DiscoveryResult(List<CardDto> cards, String nextCursor) {}

    private record CallerContext(
            UUID addressId,
            String interestedInGender,
            int minAge,
            int maxAge,
            int maxDistanceKm,
            String[] preferredResidencyTypes,
            boolean showVerifiedOnly,
            String preferredLanguage
    ) {}

    private record CursorKey(boolean isBoosted, int score, UUID userId) {}

    private record PhotoRow(UUID userId, UUID photoId, int order, boolean isPrimary,
                            String bucket, String path) {}

    private static final int SIGNED_URL_TTL_SECONDS = 3600;

    private static final String CONTEXT_SQL = """
            SELECT au.address_id,
                   dp.interested_in_gender,
                   dp.min_age,
                   dp.max_age,
                   dp.max_distance_km,
                   dp.preferred_residency_types,
                   dp.show_verified_only,
                   au.preferred_language
            FROM profiles p
            JOIN discovery_preferences dp ON dp.user_id = p.user_id
            JOIN app_users au ON au.id = p.user_id
            WHERE p.user_id = :callerId
              AND p.is_onboarded = TRUE
              AND p.is_visible = TRUE
              AND au.status = 'ACTIVE'
            """;

    /**
     * Candidates query. All filter conditions:
     * - Candidate is ACTIVE, onboarded, visible
     * - Candidate has INCOGNITO mode off
     * - Candidate has an APPROVED primary photo
     * - Gender matches caller preference
     * - Age within caller range
     * - Residency type within caller preferences (when non-empty)
     * - Verified-only filter
     * - Scope filter (NEARBY=distance, ETHIOPIA/ERITREA/DIASPORA=residency)
     * - No ACTIVE discovery action already from caller toward candidate
     * - No ACTIVE match between caller and candidate
     * - No block in either direction
     * - Not the caller themselves
     * - Cursor pagination (keyset on is_boosted DESC, score DESC, user_id ASC)
     */
    private static final String CARDS_SQL = """
            WITH ranked AS (
                SELECT
                    p.user_id,
                    p.display_name,
                    DATE_PART('year', AGE(p.date_of_birth))::int AS age,
                    p.gender,
                    p.bio,
                    p.residency_type,
                    p.relationship_intention,
                    p.profile_completion_score,
                    p.is_verified,
                    a.city,
                    a.country_code,
                    CASE WHEN :skipDistance THEN NULL
                         ELSE ROUND((ST_Distance(
                             ca.coords::geometry, a.coords::geometry
                         ) / 1000.0)::numeric, 1)
                    END AS distance_km,
                    EXISTS (
                        SELECT 1 FROM active_boosts ab
                        WHERE ab.user_id = p.user_id AND ab.expires_at > NOW()
                    ) AS is_boosted
                FROM profiles p
                JOIN app_users au ON au.id = p.user_id
                JOIN addresses a ON a.id = au.address_id
                JOIN addresses ca ON ca.id = :callerAddressId
                WHERE
                    p.is_visible = TRUE
                    AND p.is_onboarded = TRUE
                    AND au.status = 'ACTIVE'
                    AND p.discovery_mode <> 'INCOGNITO'
                    AND p.gender = :genderFilter
                    AND p.date_of_birth BETWEEN :maxDobBound AND :minDobBound
                    AND (COALESCE(ARRAY_LENGTH(:residencyFilter::text[], 1), 0) = 0
                         OR p.residency_type = ANY(:residencyFilter::text[]))
                    AND (:showVerifiedOnly = FALSE OR p.is_verified = TRUE)
                    AND (:skipDistance = TRUE
                         OR ST_DWithin(a.coords, ca.coords, :maxDistanceMeters))
                    AND (:scopeResidencyType IS NULL
                         OR p.residency_type = :scopeResidencyType)
                    AND EXISTS (
                        SELECT 1 FROM profile_photos pph
                        WHERE pph.user_id = p.user_id
                          AND pph.is_primary = TRUE
                          AND pph.moderation_status = 'APPROVED'
                          AND pph.deleted_at IS NULL
                    )
                    AND NOT EXISTS (
                        SELECT 1 FROM user_discovery_actions uda
                        WHERE uda.actor_user_id = :callerId
                          AND uda.target_user_id = p.user_id
                          AND uda.status = 'ACTIVE'
                    )
                    AND NOT EXISTS (
                        SELECT 1 FROM matches m
                        WHERE ((m.user_one_id = :callerId AND m.user_two_id = p.user_id)
                               OR (m.user_one_id = p.user_id AND m.user_two_id = :callerId))
                          AND m.status = 'ACTIVE'
                    )
                    AND NOT EXISTS (
                        SELECT 1 FROM user_blocks ub
                        WHERE (ub.blocker_user_id = :callerId AND ub.blocked_user_id = p.user_id
                               AND ub.status = 'ACTIVE')
                           OR (ub.blocker_user_id = p.user_id AND ub.blocked_user_id = :callerId
                               AND ub.status = 'ACTIVE')
                    )
                    AND p.user_id <> :callerId
            )
            SELECT * FROM ranked
            WHERE (:noCursor
                   OR (NOT is_boosted AND :cursorBoosted)
                   OR (is_boosted = :cursorBoosted AND profile_completion_score < :cursorScore)
                   OR (is_boosted = :cursorBoosted
                       AND profile_completion_score = :cursorScore
                       AND user_id > :cursorId::uuid))
            ORDER BY is_boosted DESC, profile_completion_score DESC, user_id ASC
            LIMIT :limit
            """;

    private static final String PHOTOS_SQL = """
            SELECT user_id, id AS photo_id, photo_order, is_primary, storage_bucket, storage_path
            FROM profile_photos
            WHERE user_id IN (:userIds)
              AND moderation_status = 'APPROVED'
              AND deleted_at IS NULL
            ORDER BY user_id, photo_order ASC
            """;

    private static final String PROMPTS_SQL = """
            SELECT ppa.user_id,
                   ppa.answer_text,
                   COALESCE(ppt.prompt_text, pp_base.prompt_text) AS prompt_text
            FROM profile_prompt_answers ppa
            JOIN profile_prompts pp_base ON pp_base.id = ppa.prompt_id
            LEFT JOIN profile_prompt_translations ppt
                ON ppt.prompt_id = ppa.prompt_id AND ppt.locale = :locale
            WHERE ppa.user_id IN (:userIds)
            ORDER BY ppa.user_id, ppa.created_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final SupabaseStorageService storageService;

    public DiscoveryService(NamedParameterJdbcTemplate jdbc,
                            SupabaseStorageService storageService) {
        this.jdbc = jdbc;
        this.storageService = storageService;
    }

    public DiscoveryResult getCards(UUID callerId, String cursor, int rawLimit, String scope) {
        CallerContext ctx = loadCallerContext(callerId);

        int limit = Math.min(Math.max(rawLimit, 1), 50);
        CursorKey cursorKey = decodeCursor(cursor);

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate minDobBound = today.minusYears(ctx.minAge());
        LocalDate maxDobBound = today.minusYears(ctx.maxAge()).minusDays(1);
        long maxDistanceMeters = (long) ctx.maxDistanceKm() * 1000L;

        // Scope determines distance filter and optional residency pin
        boolean isNearbyScope = scope == null || "NEARBY".equalsIgnoreCase(scope);
        boolean skipDistance = !isNearbyScope || ctx.addressId() == null;
        String scopeResidencyType = resolveScopeResidencyType(scope);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("callerId", callerId)
                .addValue("callerAddressId", ctx.addressId())
                .addValue("skipDistance", skipDistance)
                .addValue("genderFilter", ctx.interestedInGender())
                .addValue("minDobBound", minDobBound)
                .addValue("maxDobBound", maxDobBound)
                .addValue("maxDistanceMeters", maxDistanceMeters)
                .addValue("residencyFilter", toPgArrayLiteral(ctx.preferredResidencyTypes()))
                .addValue("showVerifiedOnly", ctx.showVerifiedOnly())
                .addValue("scopeResidencyType", scopeResidencyType)
                .addValue("noCursor", cursorKey == null)
                .addValue("cursorBoosted", cursorKey != null && cursorKey.isBoosted())
                .addValue("cursorScore", cursorKey != null ? cursorKey.score() : 0)
                .addValue("cursorId", cursorKey != null ? cursorKey.userId().toString() : null)
                .addValue("limit", limit);

        List<CardDto> cards = jdbc.query(CARDS_SQL, params, (rs, rowNum) -> mapCard(rs));

        if (!cards.isEmpty()) {
            List<UUID> userIds = cards.stream().map(CardDto::userId).collect(Collectors.toList());

            Map<UUID, List<PhotoCardDto>> photosByUser = fetchPhotos(userIds);
            Map<UUID, List<PromptAnswerDto>> promptsByUser = fetchPrompts(userIds, ctx.preferredLanguage());

            cards = cards.stream()
                    .map(c -> c.withPhotos(photosByUser.getOrDefault(c.userId(), List.of())))
                    .map(c -> c.withPromptAnswers(promptsByUser.getOrDefault(c.userId(), List.of())))
                    .collect(Collectors.toList());
        }

        String nextCursor = null;
        if (cards.size() == limit) {
            CardDto last = cards.get(cards.size() - 1);
            nextCursor = encodeCursor(last.isBoosted(), last.profileCompletionScore(), last.userId());
        }

        return new DiscoveryResult(cards, nextCursor);
    }

    private CallerContext loadCallerContext(UUID callerId) {
        List<CallerContext> results = jdbc.query(
                CONTEXT_SQL,
                Map.of("callerId", callerId),
                (rs, rowNum) -> new CallerContext(
                        rs.getObject("address_id", UUID.class),
                        rs.getString("interested_in_gender"),
                        rs.getInt("min_age"),
                        rs.getInt("max_age"),
                        rs.getInt("max_distance_km"),
                        extractTextArray(rs, "preferred_residency_types"),
                        rs.getBoolean("show_verified_only"),
                        defaultLocale(rs.getString("preferred_language"))
                )
        );

        if (results.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "profile_not_ready");
        }
        CallerContext ctx = results.get(0);
        if (ctx.addressId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "location_required");
        }
        return ctx;
    }

    private Map<UUID, List<PhotoCardDto>> fetchPhotos(List<UUID> userIds) {
        Map<UUID, List<PhotoCardDto>> result = new LinkedHashMap<>();
        jdbc.query(PHOTOS_SQL, Map.of("userIds", userIds), rs -> {
            UUID userId = rs.getObject("user_id", UUID.class);
            String bucket = rs.getString("storage_bucket");
            String path = rs.getString("storage_path");
            String signedUrl = (bucket != null && path != null)
                    ? storageService.generateSignedUrl(bucket, path, SIGNED_URL_TTL_SECONDS)
                    : null;
            result.computeIfAbsent(userId, k -> new ArrayList<>()).add(new PhotoCardDto(
                    rs.getObject("photo_id", UUID.class),
                    rs.getInt("photo_order"),
                    rs.getBoolean("is_primary"),
                    signedUrl
            ));
        });
        return result;
    }

    private Map<UUID, List<PromptAnswerDto>> fetchPrompts(List<UUID> userIds, String locale) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userIds", userIds)
                .addValue("locale", locale);

        Map<UUID, List<PromptAnswerDto>> result = new LinkedHashMap<>();
        jdbc.query(PROMPTS_SQL, params, rs -> {
            UUID userId = rs.getObject("user_id", UUID.class);
            List<PromptAnswerDto> answers = result.computeIfAbsent(userId, k -> new ArrayList<>());
            if (answers.size() < 2) {
                answers.add(new PromptAnswerDto(
                        rs.getString("prompt_text"),
                        rs.getString("answer_text")
                ));
            }
        });
        return result;
    }

    private CardDto mapCard(ResultSet rs) throws SQLException {
        return new CardDto(
                rs.getObject("user_id", UUID.class),
                rs.getString("display_name"),
                rs.getInt("age"),
                rs.getString("gender"),
                rs.getString("bio"),
                rs.getString("residency_type"),
                rs.getString("city"),
                rs.getString("country_code"),
                getDoubleOrNull(rs, "distance_km"),
                rs.getBoolean("is_verified"),
                rs.getBoolean("is_boosted"),
                rs.getInt("profile_completion_score"),
                rs.getString("relationship_intention"),
                new ArrayList<>(),
                new ArrayList<>()
        );
    }

    private Double getDoubleOrNull(ResultSet rs, String col) throws SQLException {
        double val = rs.getDouble(col);
        return rs.wasNull() ? null : val;
    }

    private String[] extractTextArray(ResultSet rs, String col) throws SQLException {
        java.sql.Array arr = rs.getArray(col);
        if (arr == null) return new String[0];
        return (String[]) arr.getArray();
    }

    private String toPgArrayLiteral(String[] arr) {
        if (arr == null || arr.length == 0) return "{}";
        return "{" + String.join(",", arr) + "}";
    }

    private String resolveScopeResidencyType(String scope) {
        if (scope == null || "NEARBY".equalsIgnoreCase(scope)) return null;
        return switch (scope.toUpperCase()) {
            case "ETHIOPIA" -> "ETHIOPIA";
            case "ERITREA" -> "ERITREA";
            case "DIASPORA" -> "DIASPORA";
            default -> null;
        };
    }

    private String encodeCursor(boolean isBoosted, int score, UUID userId) {
        String raw = (isBoosted ? "1" : "0") + ":" + score + ":" + userId;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private CursorKey decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split(":", 3);
            if (parts.length != 3) return null;
            boolean boosted = "1".equals(parts[0]);
            int score = Integer.parseInt(parts[1]);
            UUID userId = UUID.fromString(parts[2]);
            return new CursorKey(boosted, score, userId);
        } catch (Exception e) {
            return null;
        }
    }

    private String defaultLocale(String locale) {
        return locale != null ? locale : "en";
    }
}
