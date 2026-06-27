package com.qaliye.backend.discovery.service;

import com.qaliye.backend.activity.ActivityStatus;
import com.qaliye.backend.activity.ActivityStatusService;
import com.qaliye.backend.discovery.dto.LikeItemDto;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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

    public LikesService(NamedParameterJdbcTemplate jdbc,
                        StorageSigningService signingService,
                        ActivityStatusService activityStatusService) {
        this.jdbc = jdbc;
        this.signingService = signingService;
        this.activityStatusService = activityStatusService;
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
              AND NOT EXISTS (
                  SELECT 1 FROM matches m
                  WHERE (m.user_one_id = uda.actor_user_id AND m.user_two_id = :userId)
                     OR (m.user_one_id = :userId AND m.user_two_id = uda.actor_user_id)
              )
            ORDER BY uda.created_at DESC
            LIMIT :limit OFFSET :offset
            """;

    private static final String RECEIVED_LIKES_COUNT_SQL = """
            SELECT COUNT(*)
            FROM user_discovery_actions
            WHERE target_user_id = :userId
              AND action_type IN ('LIKE', 'SUPERLIKE')
              AND status = 'ACTIVE'
              AND NOT EXISTS (
                  SELECT 1 FROM matches m
                  WHERE (m.user_one_id = actor_user_id AND m.user_two_id = :userId)
                     OR (m.user_one_id = :userId AND m.user_two_id = actor_user_id)
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
              AND NOT EXISTS (
                  SELECT 1 FROM matches m
                  WHERE (m.user_one_id = :userId AND m.user_two_id = uda.target_user_id)
                     OR (m.user_one_id = uda.target_user_id AND m.user_two_id = :userId)
              )
            ORDER BY uda.created_at DESC
            LIMIT :limit OFFSET :offset
            """;

    private static final String SENT_LIKES_COUNT_SQL = """
            SELECT COUNT(*)
            FROM user_discovery_actions
            WHERE actor_user_id = :userId
              AND action_type IN ('LIKE', 'SUPERLIKE')
              AND status = 'ACTIVE'
              AND NOT EXISTS (
                  SELECT 1 FROM matches m
                  WHERE (m.user_one_id = :userId AND m.user_two_id = target_user_id)
                     OR (m.user_one_id = target_user_id AND m.user_two_id = :userId)
              )
            """;

    @Transactional(readOnly = true)
    public LikesPageResponse getLikes(UUID currentUserId, String direction, int page, int size) {
        String resolvedDirection = resolveDirection(direction);
        int resolvedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int resolvedPage = Math.max(page, 0);
        long offset = (long) resolvedPage * resolvedSize;

        boolean isReceived = "RECEIVED".equals(resolvedDirection);
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
                    activityStatus
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
}
