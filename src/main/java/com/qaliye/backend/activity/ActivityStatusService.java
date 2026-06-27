package com.qaliye.backend.activity;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ActivityStatusService {

    private final ActivityStatusProperties props;
    private final Clock clock;
    private final NamedParameterJdbcTemplate jdbc;

    public ActivityStatusService(ActivityStatusProperties props,
                                 Clock clock,
                                 NamedParameterJdbcTemplate jdbc) {
        this.props = props;
        this.clock = clock;
        this.jdbc = jdbc;
    }

    public ActivityStatus resolve(boolean showActivityStatus, OffsetDateTime lastActiveAt, Instant now) {
        if (!showActivityStatus) return ActivityStatus.HIDDEN;
        if (lastActiveAt == null) return ActivityStatus.OFFLINE;
        Instant lastActive = lastActiveAt.toInstant();
        long secondsAgo = Duration.between(lastActive, now).toSeconds();
        if (secondsAgo < 0) secondsAgo = 0;
        long onlineSeconds = props.getOnlineWindowSeconds();
        long recentlyActiveSeconds = props.getRecentlyActiveWindowSeconds();
        if (secondsAgo <= onlineSeconds) return ActivityStatus.ONLINE;
        if (secondsAgo <= recentlyActiveSeconds) return ActivityStatus.RECENTLY_ACTIVE;
        return ActivityStatus.OFFLINE;
    }

    public Instant now() {
        return clock.instant();
    }

    public record HeartbeatResult(ActivityStatus activityStatus, boolean showActivityStatus) {}

    @Transactional
    public HeartbeatResult heartbeat(UUID callerId) {
        requireActive(callerId);

        int minInterval = props.getHeartbeatWriteMinIntervalSeconds();
        jdbc.update("""
                UPDATE app_users
                SET last_active_at = clock_timestamp()
                WHERE id = :callerId
                  AND status = 'ACTIVE'
                  AND (last_active_at IS NULL
                       OR last_active_at < clock_timestamp() - (:minInterval * INTERVAL '1 second'))
                """,
                new MapSqlParameterSource()
                        .addValue("callerId", callerId)
                        .addValue("minInterval", minInterval));

        return jdbc.query("""
                SELECT last_active_at, show_activity_status, clock_timestamp() AS db_now
                FROM app_users
                WHERE id = :callerId AND status = 'ACTIVE'
                """,
                new MapSqlParameterSource("callerId", callerId),
                rs -> {
                    if (!rs.next()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCOUNT_NOT_ACTIVE");
                    OffsetDateTime lastActiveAt = rs.getObject("last_active_at", OffsetDateTime.class);
                    boolean showActivity = rs.getBoolean("show_activity_status");
                    OffsetDateTime dbNow = rs.getObject("db_now", OffsetDateTime.class);
                    Instant now = dbNow != null ? dbNow.toInstant() : Instant.now(clock);
                    ActivityStatus status = resolve(showActivity, lastActiveAt, now);
                    return new HeartbeatResult(status, showActivity);
                });
    }

    public record BatchStatusItem(UUID userId, ActivityStatus activityStatus) {}

    private static final String BATCH_STATUS_SQL = """
            SELECT au.id, au.last_active_at, au.show_activity_status, clock_timestamp() AS db_now
            FROM app_users au
            WHERE au.id = ANY(:targetIds::uuid[])
              AND au.status = 'ACTIVE'
              AND au.deleted_at IS NULL
              AND NOT EXISTS (
                  SELECT 1 FROM user_blocks ub
                  WHERE ub.status = 'ACTIVE'
                    AND ((ub.blocker_user_id = :callerId AND ub.blocked_user_id = au.id)
                      OR (ub.blocker_user_id = au.id AND ub.blocked_user_id = :callerId))
              )
              AND (
                  EXISTS (
                      SELECT 1 FROM matches m
                      WHERE m.status = 'ACTIVE'
                        AND ((m.user_one_id = :callerId AND m.user_two_id = au.id)
                          OR (m.user_one_id = au.id AND m.user_two_id = :callerId))
                  )
                  OR EXISTS (
                      SELECT 1 FROM user_discovery_actions uda
                      WHERE uda.status = 'ACTIVE'
                        AND uda.action_type IN ('LIKE', 'SUPERLIKE')
                        AND ((uda.actor_user_id = :callerId AND uda.target_user_id = au.id)
                          OR (uda.actor_user_id = au.id AND uda.target_user_id = :callerId))
                  )
                  OR EXISTS (
                      SELECT 1 FROM profiles p
                      WHERE p.user_id = au.id
                        AND p.is_visible = TRUE
                        AND p.is_onboarded = TRUE
                  )
              )
            """;

    @Transactional(readOnly = true)
    public List<BatchStatusItem> getBatchStatuses(UUID callerId, List<UUID> targetIds) {
        requireActive(callerId);

        String idsParam = buildUuidArrayParam(targetIds);
        Instant capturedNow = clock.instant();

        List<BatchStatusItem> result = new ArrayList<>();
        jdbc.query(BATCH_STATUS_SQL,
                new MapSqlParameterSource()
                        .addValue("callerId", callerId)
                        .addValue("targetIds", idsParam),
                rs -> {
                    UUID userId = rs.getObject("id", UUID.class);
                    OffsetDateTime lastActiveAt = rs.getObject("last_active_at", OffsetDateTime.class);
                    boolean showActivity = rs.getBoolean("show_activity_status");
                    OffsetDateTime dbNow = rs.getObject("db_now", OffsetDateTime.class);
                    Instant now = dbNow != null ? dbNow.toInstant() : capturedNow;
                    ActivityStatus status = resolve(showActivity, lastActiveAt, now);
                    result.add(new BatchStatusItem(userId, status));
                });
        return result;
    }

    private void requireActive(UUID callerId) {
        Boolean active = jdbc.queryForObject(
                "SELECT 1 FROM app_users WHERE id = :id AND status = 'ACTIVE' AND deleted_at IS NULL",
                new MapSqlParameterSource("id", callerId),
                Boolean.class);
        if (!Boolean.TRUE.equals(active)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCOUNT_NOT_ACTIVE");
        }
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
}
