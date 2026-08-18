package com.qaliye.backend.discovery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaliye.backend.activity.ActivityStatus;
import com.qaliye.backend.activity.ActivityStatusService;
import com.qaliye.backend.billing.repository.ActionLimitRepository;
import com.qaliye.backend.discovery.dto.LikeItemDto;
import com.qaliye.backend.discovery.dto.LikesAndMatchesCountDto;
import com.qaliye.backend.discovery.dto.LikesPageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class LikesService {

    private static final Logger log = LoggerFactory.getLogger(LikesService.class);

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    private static final Set<String> VALID_DIRECTIONS = Set.of("RECEIVED", "SENT");

    private final NamedParameterJdbcTemplate jdbc;
    private final StorageSigningService signingService;
    private final ActivityStatusService activityStatusService;
    private final ActionLimitRepository actionLimitRepo;
    private final ObjectMapper objectMapper;

    public LikesService(NamedParameterJdbcTemplate jdbc,
                        StorageSigningService signingService,
                        ActivityStatusService activityStatusService,
                        ActionLimitRepository actionLimitRepo,
                        ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.signingService = signingService;
        this.activityStatusService = activityStatusService;
        this.actionLimitRepo = actionLimitRepo;
        this.objectMapper = objectMapper;
    }

    private record RevealConfig(
            boolean seeWhoLikedYouFeature,
            UUID ruleId,
            long memberCreditCost,
            Integer limitValue,
            boolean applyAfterLimit,
            LocalDate periodStart,
            LocalDate periodEnd
    ) {
        boolean isUnlimited() {
            return seeWhoLikedYouFeature
                    || ruleId == null
                    || (memberCreditCost == 0 && limitValue == null);
        }
        boolean isLimitedFree() {
            return !isUnlimited() && memberCreditCost == 0 && limitValue != null;
        }
    }

    /**
     * Likes/superlikes received by the current user, ordered newest-first.
     * Joins on the actor's profile, their approved primary photo, and address.
     * Calculates distance from current user to the actor.
     */
    private static final String RECEIVED_LIKES_SQL = """
            SELECT
                uda.id              AS action_id,
                uda.actor_user_id   AS other_user_id,
                uda.action_type,
                uda.created_at,
                uda.revealed_at,
                p.display_name,
                DATE_PART('year', AGE(p.date_of_birth))::int AS age,
                p.is_verified,
                pp.storage_bucket,
                pp.storage_path,
                CASE WHEN au.address_id IS NULL OR cu.address_id IS NULL THEN NULL
                     ELSE GREATEST(1, ROUND(
                         ST_Distance(ca.coords::geography, a.coords::geography) / 1000.0
                     )::INTEGER)
                END AS distance_km,
                a.city,
                a.region,
                a.country_name,
                au.last_active_at,
                au.show_activity_status
            FROM user_discovery_actions uda
            JOIN profiles p ON p.user_id = uda.actor_user_id
            JOIN app_users au ON au.id = uda.actor_user_id
            LEFT JOIN addresses a ON a.id = au.address_id
            JOIN app_users cu ON cu.id = :userId
            LEFT JOIN addresses ca ON ca.id = cu.address_id
            LEFT JOIN profile_photos pp
                   ON pp.user_id = uda.actor_user_id
                  AND pp.is_primary = TRUE
                  AND pp.moderation_status = 'APPROVED'
                  AND pp.deleted_at IS NULL
            WHERE uda.target_user_id = :userId
              AND uda.action_type IN ('LIKE', 'SUPERLIKE')
              AND uda.status = 'ACTIVE'
              AND au.status = 'ACTIVE'
              AND au.deleted_at IS NULL
              AND NOT EXISTS (
                  SELECT 1 FROM matches m
                  WHERE m.status = 'ACTIVE'
                    AND ((m.user_one_id = uda.actor_user_id AND m.user_two_id = :userId)
                     OR (m.user_one_id = :userId AND m.user_two_id = uda.actor_user_id))
              )
              AND NOT EXISTS (
                  SELECT 1 FROM user_blocks ub
                  WHERE ub.status = 'ACTIVE'
                    AND (
                        (ub.blocker_user_id = :userId AND ub.blocked_user_id = uda.actor_user_id)
                        OR
                        (ub.blocker_user_id = uda.actor_user_id AND ub.blocked_user_id = :userId)
                    )
              )
            ORDER BY
                CASE WHEN uda.revealed_at IS NOT NULL THEN 0 ELSE 1 END ASC,
                uda.created_at DESC
            LIMIT :limit OFFSET :offset
            """;

    private static final String RECEIVED_LIKES_COUNT_SQL = """
            SELECT COUNT(*)
            FROM user_discovery_actions uda
            JOIN app_users au ON au.id = uda.actor_user_id
            WHERE uda.target_user_id = :userId
              AND uda.action_type IN ('LIKE', 'SUPERLIKE')
              AND uda.status = 'ACTIVE'
              AND au.status = 'ACTIVE'
              AND au.deleted_at IS NULL
              AND NOT EXISTS (
                  SELECT 1 FROM matches m
                  WHERE m.status = 'ACTIVE'
                    AND ((m.user_one_id = uda.actor_user_id AND m.user_two_id = :userId)
                     OR (m.user_one_id = :userId AND m.user_two_id = uda.actor_user_id))
              )
              AND NOT EXISTS (
                  SELECT 1 FROM user_blocks ub
                  WHERE ub.status = 'ACTIVE'
                    AND (
                        (ub.blocker_user_id = :userId AND ub.blocked_user_id = uda.actor_user_id)
                        OR
                        (ub.blocker_user_id = uda.actor_user_id AND ub.blocked_user_id = :userId)
                    )
              )
            """;

    /**
     * Likes/superlikes sent by the current user, ordered newest-first.
     * Joins on the target's profile, their approved primary photo, and address.
     * Calculates distance from current user to the target.
     */
    private static final String SENT_LIKES_SQL = """
            SELECT
                uda.id              AS action_id,
                uda.target_user_id  AS other_user_id,
                uda.action_type,
                uda.created_at,
                p.display_name,
                DATE_PART('year', AGE(p.date_of_birth))::int AS age,
                p.is_verified,
                pp.storage_bucket,
                pp.storage_path,
                CASE WHEN au.address_id IS NULL OR cu.address_id IS NULL THEN NULL
                     ELSE GREATEST(1, ROUND(
                         ST_Distance(ca.coords::geography, a.coords::geography) / 1000.0
                     )::INTEGER)
                END AS distance_km,
                a.city,
                a.region,
                a.country_name,
                au.last_active_at,
                au.show_activity_status
            FROM user_discovery_actions uda
            JOIN profiles p ON p.user_id = uda.target_user_id
            JOIN app_users au ON au.id = uda.target_user_id
            LEFT JOIN addresses a ON a.id = au.address_id
            JOIN app_users cu ON cu.id = :userId
            LEFT JOIN addresses ca ON ca.id = cu.address_id
            LEFT JOIN profile_photos pp
                   ON pp.user_id = uda.target_user_id
                  AND pp.is_primary = TRUE
                  AND pp.moderation_status = 'APPROVED'
                  AND pp.deleted_at IS NULL
            WHERE uda.actor_user_id = :userId
              AND uda.action_type IN ('LIKE', 'SUPERLIKE')
              AND uda.status = 'ACTIVE'
              AND au.status = 'ACTIVE'
              AND au.deleted_at IS NULL
              AND NOT EXISTS (
                  SELECT 1 FROM matches m
                  WHERE m.status = 'ACTIVE'
                    AND ((m.user_one_id = :userId AND m.user_two_id = uda.target_user_id)
                     OR (m.user_one_id = uda.target_user_id AND m.user_two_id = :userId))
              )
              AND NOT EXISTS (
                  SELECT 1 FROM user_blocks ub
                  WHERE ub.status = 'ACTIVE'
                    AND (
                        (ub.blocker_user_id = :userId AND ub.blocked_user_id = uda.target_user_id)
                        OR
                        (ub.blocker_user_id = uda.target_user_id AND ub.blocked_user_id = :userId)
                    )
              )
            ORDER BY uda.created_at DESC
            LIMIT :limit OFFSET :offset
            """;

    private static final String SENT_LIKES_COUNT_SQL = """
            SELECT COUNT(*)
            FROM user_discovery_actions uda
            JOIN app_users au ON au.id = uda.target_user_id
            WHERE uda.actor_user_id = :userId
              AND uda.action_type IN ('LIKE', 'SUPERLIKE')
              AND uda.status = 'ACTIVE'
              AND au.status = 'ACTIVE'
              AND au.deleted_at IS NULL
              AND NOT EXISTS (
                  SELECT 1 FROM matches m
                  WHERE m.status = 'ACTIVE'
                    AND ((m.user_one_id = :userId AND m.user_two_id = uda.target_user_id)
                     OR (m.user_one_id = uda.target_user_id AND m.user_two_id = :userId))
              )
              AND NOT EXISTS (
                  SELECT 1 FROM user_blocks ub
                  WHERE ub.status = 'ACTIVE'
                    AND (
                        (ub.blocker_user_id = :userId AND ub.blocked_user_id = uda.target_user_id)
                        OR
                        (ub.blocker_user_id = uda.target_user_id AND ub.blocked_user_id = :userId)
                    )
              )
            """;

    private static final String LIKES_AND_MATCHES_COUNT_SQL = """
            SELECT
              (SELECT COUNT(*)
               FROM user_discovery_actions uda
               JOIN app_users au ON au.id = uda.actor_user_id
               WHERE uda.target_user_id = :userId
                 AND uda.action_type IN ('LIKE', 'SUPERLIKE')
                 AND uda.status = 'ACTIVE'
                 AND au.status = 'ACTIVE'
                 AND au.deleted_at IS NULL
                 AND NOT EXISTS (
                     SELECT 1 FROM matches m
                     WHERE m.status = 'ACTIVE'
                       AND ((m.user_one_id = uda.actor_user_id AND m.user_two_id = :userId)
                        OR (m.user_one_id = :userId AND m.user_two_id = uda.actor_user_id))
                 )
                 AND NOT EXISTS (
                     SELECT 1 FROM user_blocks ub
                     WHERE ub.status = 'ACTIVE'
                       AND ((ub.blocker_user_id = :userId AND ub.blocked_user_id = uda.actor_user_id)
                        OR (ub.blocker_user_id = uda.actor_user_id AND ub.blocked_user_id = :userId))
                 )
              ) AS received_likes_count,
              (SELECT COUNT(*)
               FROM user_discovery_actions uda
               JOIN app_users au ON au.id = uda.target_user_id
               WHERE uda.actor_user_id = :userId
                 AND uda.action_type IN ('LIKE', 'SUPERLIKE')
                 AND uda.status = 'ACTIVE'
                 AND au.status = 'ACTIVE'
                 AND au.deleted_at IS NULL
                 AND NOT EXISTS (
                     SELECT 1 FROM matches m
                     WHERE m.status = 'ACTIVE'
                       AND ((m.user_one_id = :userId AND m.user_two_id = uda.target_user_id)
                        OR (m.user_one_id = uda.target_user_id AND m.user_two_id = :userId))
                 )
                 AND NOT EXISTS (
                     SELECT 1 FROM user_blocks ub
                     WHERE ub.status = 'ACTIVE'
                       AND ((ub.blocker_user_id = :userId AND ub.blocked_user_id = uda.target_user_id)
                        OR (ub.blocker_user_id = uda.target_user_id AND ub.blocked_user_id = :userId))
                 )
              ) AS sent_likes_count,
              (SELECT COUNT(*)
               FROM matches m
               WHERE m.status = 'ACTIVE'
                 AND (m.user_one_id = :userId OR m.user_two_id = :userId)
              ) AS matches_count
            """;

    private static final String REVEAL_CONFIG_SQL = """
            WITH user_country AS (
                SELECT COALESCE(a.country_code, 'GLOBAL') AS cc
                FROM app_users au
                LEFT JOIN addresses a ON a.id = au.address_id
                WHERE au.id = :userId
            ),
            active_plan AS (
                SELECT sp.id AS plan_id, sp.features,
                       us.current_period_start, us.current_period_end
                FROM user_subscriptions us
                JOIN subscription_plans sp ON sp.id = us.plan_id
                WHERE us.user_id = :userId
                  AND us.status IN ('ACTIVE', 'PENDING_VERIFICATION')
                  AND sp.is_active = TRUE
                ORDER BY CASE sp.plan_kind WHEN 'PAID' THEN 0 ELSE 1 END
                LIMIT 1
            ),
            free_plan AS (
                SELECT sp.id AS plan_id, sp.features,
                       NULL::TIMESTAMPTZ AS current_period_start,
                       NULL::TIMESTAMPTZ AS current_period_end
                FROM subscription_plans sp
                CROSS JOIN user_country uc
                WHERE sp.plan_kind = 'FREE' AND sp.is_active = TRUE
                  AND sp.country_code IN (uc.cc, 'GLOBAL')
                ORDER BY CASE WHEN sp.country_code = uc.cc THEN 0 ELSE 1 END
                LIMIT 1
            ),
            rp AS (
                SELECT * FROM active_plan
                UNION ALL
                SELECT * FROM free_plan
                WHERE NOT EXISTS (SELECT 1 FROM active_plan)
                LIMIT 1
            )
            SELECT rp.features,
                   swr.id                    AS rule_id,
                   swr.member_credit_cost,
                   swr.limit_value,
                   swr.period_type,
                   swr.apply_credit_after_limit,
                   rp.current_period_start,
                   rp.current_period_end
            FROM rp
            LEFT JOIN (
                SELECT splac.id, splac.subscription_plan_id, splac.member_credit_cost,
                       splac.limit_value, splac.period_type, splac.apply_credit_after_limit
                FROM subscription_plan_limit_and_cost splac
                JOIN feature_actions fa ON fa.id = splac.feature_action_id
                WHERE fa.code = 'SEE_WHO_LIKED_YOU'
            ) swr ON swr.subscription_plan_id = rp.plan_id
            """;

    private static final String BATCH_REVEAL_ALL_SQL = """
            UPDATE user_discovery_actions uda
            SET revealed_at = NOW()
            WHERE uda.target_user_id = :userId
              AND uda.action_type IN ('LIKE', 'SUPERLIKE')
              AND uda.status = 'ACTIVE'
              AND uda.revealed_at IS NULL
              AND EXISTS (
                  SELECT 1 FROM app_users au
                  WHERE au.id = uda.actor_user_id
                    AND au.status = 'ACTIVE' AND au.deleted_at IS NULL
              )
              AND NOT EXISTS (
                  SELECT 1 FROM matches m WHERE m.status = 'ACTIVE'
                    AND ((m.user_one_id = uda.actor_user_id AND m.user_two_id = :userId)
                     OR (m.user_one_id = :userId AND m.user_two_id = uda.actor_user_id))
              )
              AND NOT EXISTS (
                  SELECT 1 FROM user_blocks ub WHERE ub.status = 'ACTIVE'
                    AND ((ub.blocker_user_id = :userId AND ub.blocked_user_id = uda.actor_user_id)
                     OR (ub.blocker_user_id = uda.actor_user_id AND ub.blocked_user_id = :userId))
              )
            """;

    private static final String BATCH_REVEAL_OLDEST_N_SQL = """
            UPDATE user_discovery_actions uda
            SET revealed_at = NOW()
            FROM (
                SELECT uda2.id
                FROM user_discovery_actions uda2
                JOIN app_users au ON au.id = uda2.actor_user_id
                WHERE uda2.target_user_id = :userId
                  AND uda2.action_type IN ('LIKE', 'SUPERLIKE')
                  AND uda2.status = 'ACTIVE'
                  AND uda2.revealed_at IS NULL
                  AND au.status = 'ACTIVE'
                  AND au.deleted_at IS NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM matches m WHERE m.status = 'ACTIVE'
                        AND ((m.user_one_id = uda2.actor_user_id AND m.user_two_id = :userId)
                         OR (m.user_one_id = :userId AND m.user_two_id = uda2.actor_user_id))
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM user_blocks ub WHERE ub.status = 'ACTIVE'
                        AND ((ub.blocker_user_id = :userId AND ub.blocked_user_id = uda2.actor_user_id)
                         OR (ub.blocker_user_id = uda2.actor_user_id AND ub.blocked_user_id = :userId))
                  )
                ORDER BY uda2.created_at ASC
                LIMIT :revealCount
            ) AS oldest
            WHERE uda.id = oldest.id
            RETURNING uda.id
            """;

    @Transactional(readOnly = true)
    public LikesAndMatchesCountDto getLikesAndMatchesCount(UUID currentUserId) {
        var params = new MapSqlParameterSource("userId", currentUserId);
        return jdbc.queryForObject(LIKES_AND_MATCHES_COUNT_SQL, params, (rs, rowNum) ->
                new LikesAndMatchesCountDto(
                        rs.getLong("received_likes_count"),
                        rs.getLong("sent_likes_count"),
                        rs.getLong("matches_count")
                ));
    }

    @Transactional
    public LikesPageResponse getLikes(UUID currentUserId, String direction, int page, int size) {
        String resolvedDirection = resolveDirection(direction);
        int resolvedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int resolvedPage = Math.max(page, 0);
        long offset = (long) resolvedPage * resolvedSize;

        boolean isReceived = "RECEIVED".equals(resolvedDirection);

        if (isReceived) {
            applyRevealLogic(currentUserId);
        }

        String dataSql = isReceived ? RECEIVED_LIKES_SQL : SENT_LIKES_SQL;
        String countSql = isReceived ? RECEIVED_LIKES_COUNT_SQL : SENT_LIKES_COUNT_SQL;

        var countParams = new MapSqlParameterSource("userId", currentUserId);
        Long total = jdbc.queryForObject(countSql, countParams, Long.class);
        long totalElements = total != null ? total : 0L;
        int totalPages = (int) Math.ceil((double) totalElements / resolvedSize);

        var dataParams = new MapSqlParameterSource()
                .addValue("userId", currentUserId)
                .addValue("limit", resolvedSize)
                .addValue("offset", offset);

        Instant capturedNow = activityStatusService.now();

        List<LikeItemDto> items = new ArrayList<>();
        jdbc.query(dataSql, dataParams, rs -> {
            String bucket = rs.getString("storage_bucket");
            String path = rs.getString("storage_path");
            String photoUrl = null;
            if (bucket != null && path != null) {
                photoUrl = signingService.sign(bucket, path);
                log.debug("  [photo] bucket={} path={} signedUrl={}", bucket, path, photoUrl);
            } else {
                log.debug("  [photo] no approved primary photo for userId={}",
                        rs.getObject("other_user_id", UUID.class));
            }

            Object createdAtObj = rs.getObject("created_at");
            Instant likedAt = createdAtObj instanceof OffsetDateTime odt
                    ? odt.toInstant()
                    : Instant.now();

            Instant revealedAt = isReceived ? toInstant(rs.getObject("revealed_at")) : null;

            Integer distanceKm = getDoubleOrNull(rs, "distance_km");
            OffsetDateTime lastActiveAt = rs.getObject("last_active_at", OffsetDateTime.class);
            boolean showActivity = rs.getBoolean("show_activity_status");
            ActivityStatus activityStatus = activityStatusService.resolve(showActivity, lastActiveAt, capturedNow);

            items.add(new LikeItemDto(
                    rs.getObject("action_id", UUID.class),
                    rs.getObject("other_user_id", UUID.class),
                    rs.getString("display_name"),
                    rs.getInt("age"),
                    rs.getBoolean("is_verified"),
                    photoUrl,
                    rs.getString("action_type"),
                    likedAt,
                    distanceKm,
                    rs.getString("city"),
                    rs.getString("region"),
                    rs.getString("country_name"),
                    activityStatus,
                    revealedAt
            ));
        });

        LikesPageResponse response = new LikesPageResponse(
                items,
                resolvedPage,
                resolvedSize,
                totalElements,
                totalPages,
                resolvedPage < totalPages - 1,
                resolvedPage > 0,
                resolvedDirection
        );

        log.debug("[LikesService] userId={} direction={} page={} size={} totalElements={} returned={} items",
                currentUserId, resolvedDirection, resolvedPage, resolvedSize, totalElements, items.size());
        items.forEach(item -> log.debug("  -> actionId={} userId={} displayName={} age={} actionType={} likedAt={} distanceKm={} city={} countryName={}",
                item.actionId(), item.userId(), item.displayName(), item.age(),
                item.actionType(), item.likedAt(), item.distanceKm(), item.city(), item.countryName()));

        return response;
    }

    private String resolveDirection(String direction) {
        if (direction == null) return "RECEIVED";
        String upper = direction.toUpperCase();
        if (!VALID_DIRECTIONS.contains(upper)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid direction '" + direction + "'. Must be RECEIVED or SENT.");
        }
        return upper;
    }

    private Integer getDoubleOrNull(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        double val = rs.getDouble(col);
        return rs.wasNull() ? null : (int) Math.round(val);
    }

    private void applyRevealLogic(UUID userId) {
        RevealConfig config = loadRevealConfig(userId);
        if (config.isUnlimited()) {
            batchRevealAll(userId);
        } else if (config.isLimitedFree()) {
            batchRevealOldestN(userId, config);
        }
        // memberCreditCost > 0: no auto-reveal; user reveals individually via POST /actions/{id}/reveal
    }

    private RevealConfig loadRevealConfig(UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                REVEAL_CONFIG_SQL, new MapSqlParameterSource("userId", userId));
        if (rows.isEmpty()) {
            return new RevealConfig(false, null, 0, null, false, LocalDate.now(), LocalDate.now());
        }
        Map<String, Object> row = rows.get(0);

        String featuresJson = row.get("features") != null ? row.get("features").toString() : null;
        boolean seeWhoLikedYou = parseSeeWhoLikedYouFeature(featuresJson);

        UUID ruleId = (UUID) row.get("rule_id");
        long memberCreditCost = ruleId != null && row.get("member_credit_cost") != null
                ? ((Number) row.get("member_credit_cost")).longValue() : 0L;
        Integer limitValue = ruleId != null && row.get("limit_value") != null
                ? ((Number) row.get("limit_value")).intValue() : null;
        boolean applyAfterLimit = ruleId != null && Boolean.TRUE.equals(row.get("apply_credit_after_limit"));
        String periodType = ruleId != null ? (String) row.get("period_type") : "MONTH";

        LocalDate[] period = resolvePeriod(periodType,
                row.get("current_period_start"), row.get("current_period_end"));

        return new RevealConfig(seeWhoLikedYou, ruleId, memberCreditCost,
                limitValue, applyAfterLimit, period[0], period[1]);
    }

    private void batchRevealAll(UUID userId) {
        int count = jdbc.update(BATCH_REVEAL_ALL_SQL, new MapSqlParameterSource("userId", userId));
        if (count > 0) {
            log.debug("Auto-revealed {} likes for user={} (unlimited)", count, userId);
        }
    }

    private void batchRevealOldestN(UUID userId, RevealConfig config) {
        actionLimitRepo.ensureExists(userId, config.ruleId(), config.periodStart(), config.periodEnd());

        ActionLimitRepository.TrackerRow tracker =
                actionLimitRepo.findForUpdate(userId, config.ruleId(), config.periodStart())
                        .orElseThrow(() -> new IllegalStateException(
                                "Tracker row missing after ensureExists for user=" + userId));

        int remaining = Math.max(0, config.limitValue() - tracker.usedCount());
        if (remaining == 0) {
            return;
        }

        List<UUID> revealed = jdbc.query(
                BATCH_REVEAL_OLDEST_N_SQL,
                new MapSqlParameterSource("userId", userId).addValue("revealCount", remaining),
                (rs, rn) -> rs.getObject("id", UUID.class));

        int actualCount = revealed.size();
        if (actualCount > 0) {
            actionLimitRepo.incrementBy(tracker.id(), actualCount);
            log.debug("Auto-revealed {}/{} free reveals for user={} (limit={})",
                    actualCount, remaining, userId, config.limitValue());
        }
    }

    private boolean parseSeeWhoLikedYouFeature(String featuresJson) {
        if (featuresJson == null || featuresJson.isBlank()) return false;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> features = objectMapper.readValue(featuresJson, Map.class);
            Object val = features.get("seeWhoLikedYou");
            if (val instanceof Boolean b) return b;
            if (val instanceof String s) return "true".equalsIgnoreCase(s);
            return false;
        } catch (Exception e) {
            log.warn("Failed to parse plan features JSON for reveal config: {}", featuresJson);
            return false;
        }
    }

    private LocalDate[] resolvePeriod(String periodType, Object subPeriodStart, Object subPeriodEnd) {
        LocalDate today = LocalDate.now();
        return switch (periodType != null ? periodType : "MONTH") {
            case "DAY" -> new LocalDate[]{today, today};
            case "BILLING_CYCLE" -> {
                if (subPeriodStart != null && subPeriodEnd != null) {
                    LocalDate s = toLocalDate(subPeriodStart);
                    LocalDate e = toLocalDate(subPeriodEnd);
                    if (s != null && e != null) yield new LocalDate[]{s, e};
                }
                yield new LocalDate[]{today.withDayOfMonth(1),
                        today.withDayOfMonth(today.lengthOfMonth())};
            }
            default -> new LocalDate[]{today.withDayOfMonth(1),
                    today.withDayOfMonth(today.lengthOfMonth())};
        };
    }

    private LocalDate toLocalDate(Object obj) {
        if (obj instanceof java.sql.Date d) return d.toLocalDate();
        if (obj instanceof OffsetDateTime odt) return odt.toLocalDate();
        if (obj instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        return null;
    }

    private Instant toInstant(Object obj) {
        if (obj == null) return null;
        if (obj instanceof OffsetDateTime odt) return odt.toInstant();
        if (obj instanceof java.sql.Timestamp ts) return ts.toInstant();
        return null;
    }
}
