package com.qaliye.backend.discovery.service;

import com.qaliye.backend.activity.ActivityStatus;
import com.qaliye.backend.activity.ActivityStatusService;
import com.qaliye.backend.discovery.dto.MatchItemDto;
import com.qaliye.backend.discovery.dto.MatchesPageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class MatchesService {

    private static final Logger log = LoggerFactory.getLogger(MatchesService.class);

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private final NamedParameterJdbcTemplate jdbc;
    private final StorageSigningService signingService;
    private final ActivityStatusService activityStatusService;

    public MatchesService(NamedParameterJdbcTemplate jdbc,
                          StorageSigningService signingService,
                          ActivityStatusService activityStatusService) {
        this.jdbc = jdbc;
        this.signingService = signingService;
        this.activityStatusService = activityStatusService;
    }

    /**
     * Fetches ACTIVE matches for the current user.
     *
     * Ordering: most recently active conversation first (last_message_at DESC NULLS LAST),
     * then newest match for those without any messages (matched_at DESC).
     *
     * The current user can be either user_one_id or user_two_id — CASE expressions
     * resolve the "other user" and the correct last-read timestamp for unread detection.
     *
     * Distance is computed geodesically via ST_Distance(::geography) consistent with
     * the rest of the discovery service.
     */
    private static final String MATCHES_SQL = """
            SELECT
                m.id                                                        AS match_id,
                CASE WHEN m.user_one_id = :userId
                     THEN m.user_two_id
                     ELSE m.user_one_id
                END                                                         AS other_user_id,
                m.matched_at,
                m.rewind_eligible_until,
                m.first_message_at,
                m.last_message_at,
                CASE WHEN m.last_message_at IS NOT NULL
                          AND (CASE WHEN m.user_one_id = :userId
                                    THEN m.user_one_last_read_at
                                    ELSE m.user_two_last_read_at
                               END) < m.last_message_at
                     THEN TRUE
                     ELSE FALSE
                END                                                         AS is_unread,
                p.display_name,
                DATE_PART('year', AGE(p.date_of_birth))::int                AS age,
                p.is_verified,
                pp.storage_bucket,
                pp.storage_path,
                CASE WHEN au.address_id IS NULL OR cu.address_id IS NULL THEN NULL
                     ELSE GREATEST(1, ROUND(
                         ST_Distance(ca.coords::geography, a.coords::geography) / 1000.0
                     )::INTEGER)
                END                                                         AS distance_km,
                a.city,
                a.region,
                a.country_name,
                au.last_active_at,
                au.show_activity_status
            FROM matches m
            JOIN profiles p
                ON p.user_id = CASE WHEN m.user_one_id = :userId
                                    THEN m.user_two_id
                                    ELSE m.user_one_id
                               END
            JOIN app_users au
                ON au.id = CASE WHEN m.user_one_id = :userId
                                THEN m.user_two_id
                                ELSE m.user_one_id
                           END
            LEFT JOIN addresses a ON a.id = au.address_id
            JOIN app_users cu ON cu.id = :userId
            LEFT JOIN addresses ca ON ca.id = cu.address_id
            LEFT JOIN profile_photos pp
                   ON pp.user_id = CASE WHEN m.user_one_id = :userId
                                        THEN m.user_two_id
                                        ELSE m.user_one_id
                                   END
                  AND pp.is_primary = TRUE
                  AND pp.moderation_status = 'APPROVED'
                  AND pp.deleted_at IS NULL
            WHERE (m.user_one_id = :userId OR m.user_two_id = :userId)
              AND m.status = 'ACTIVE'
            ORDER BY m.last_message_at DESC NULLS LAST, m.matched_at DESC
            LIMIT :limit OFFSET :offset
            """;

    private static final String MATCHES_COUNT_SQL = """
            SELECT COUNT(*)
            FROM matches
            WHERE (user_one_id = :userId OR user_two_id = :userId)
              AND status = 'ACTIVE'
            """;

    @Transactional(readOnly = true)
    public MatchesPageResponse getMatches(UUID currentUserId, int page, int size) {
        int resolvedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int resolvedPage = Math.max(page, 0);
        long offset = (long) resolvedPage * resolvedSize;

        var countParams = new MapSqlParameterSource("userId", currentUserId);
        Long total = jdbc.queryForObject(MATCHES_COUNT_SQL, countParams, Long.class);
        long totalElements = total != null ? total : 0L;
        int totalPages = (int) Math.ceil((double) totalElements / resolvedSize);

        var dataParams = new MapSqlParameterSource()
                .addValue("userId", currentUserId)
                .addValue("limit", resolvedSize)
                .addValue("offset", offset);

        Instant capturedNow = activityStatusService.now();

        List<MatchItemDto> items = new ArrayList<>();
        jdbc.query(MATCHES_SQL, dataParams, rs -> {
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

            Instant matchedAt = toInstant(rs.getObject("matched_at"));
            Instant rewindEligibleUntil = toInstant(rs.getObject("rewind_eligible_until"));
            Instant firstMessageAt = toInstant(rs.getObject("first_message_at"));
            Instant lastMessageAt = toInstant(rs.getObject("last_message_at"));

            Integer distanceKm = getIntOrNull(rs, "distance_km");
            OffsetDateTime lastActiveAt = rs.getObject("last_active_at", OffsetDateTime.class);
            boolean showActivity = rs.getBoolean("show_activity_status");
            ActivityStatus activityStatus = activityStatusService.resolve(showActivity, lastActiveAt, capturedNow);

            items.add(new MatchItemDto(
                    rs.getObject("match_id", UUID.class),
                    rs.getObject("other_user_id", UUID.class),
                    rs.getString("display_name"),
                    rs.getInt("age"),
                    rs.getBoolean("is_verified"),
                    photoUrl,
                    matchedAt,
                    rewindEligibleUntil,
                    firstMessageAt,
                    lastMessageAt,
                    firstMessageAt != null,
                    rs.getBoolean("is_unread"),
                    distanceKm,
                    rs.getString("city"),
                    rs.getString("region"),
                    rs.getString("country_name"),
                    activityStatus
            ));
        });

        MatchesPageResponse response = new MatchesPageResponse(
                items,
                resolvedPage,
                resolvedSize,
                totalElements,
                totalPages,
                resolvedPage < totalPages - 1,
                resolvedPage > 0
        );

        log.debug("[MatchesService] userId={} page={} size={} totalElements={} returned={} items",
                currentUserId, resolvedPage, resolvedSize, totalElements, items.size());
        items.forEach(item -> log.debug(
                "  -> matchId={} userId={} displayName={} matchedAt={} hasConversation={} isUnread={} distanceKm={} city={}",
                item.matchId(), item.userId(), item.displayName(), item.matchedAt(),
                item.hasConversation(), item.isUnread(), item.distanceKm(), item.city()));

        return response;
    }

    private static Instant toInstant(Object obj) {
        if (obj instanceof OffsetDateTime odt) return odt.toInstant();
        return null;
    }

    private Integer getIntOrNull(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        int val = rs.getInt(col);
        return rs.wasNull() ? null : val;
    }
}
