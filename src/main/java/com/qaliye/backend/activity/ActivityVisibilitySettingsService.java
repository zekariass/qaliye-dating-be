package com.qaliye.backend.activity;

import com.qaliye.backend.activity.dto.ActivityVisibilityResponse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class ActivityVisibilitySettingsService {

    private final NamedParameterJdbcTemplate jdbc;
    private final ActivityStatusService activityStatusService;

    public ActivityVisibilitySettingsService(NamedParameterJdbcTemplate jdbc,
                                             ActivityStatusService activityStatusService) {
        this.jdbc = jdbc;
        this.activityStatusService = activityStatusService;
    }

    @Transactional
    public ActivityVisibilityResponse updateVisibility(UUID callerId, boolean showActivityStatus) {
        int updated = jdbc.update("""
                UPDATE app_users
                SET show_activity_status = :showActivityStatus
                WHERE id = :callerId
                  AND status = 'ACTIVE'
                  AND deleted_at IS NULL
                """,
                new MapSqlParameterSource()
                        .addValue("callerId", callerId)
                        .addValue("showActivityStatus", showActivityStatus));

        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCOUNT_NOT_ACTIVE");
        }

        return jdbc.query("""
                SELECT last_active_at, show_activity_status, clock_timestamp() AS db_now
                FROM app_users
                WHERE id = :callerId
                """,
                new MapSqlParameterSource("callerId", callerId),
                rs -> {
                    if (!rs.next()) {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCOUNT_NOT_ACTIVE");
                    }
                    OffsetDateTime lastActiveAt = rs.getObject("last_active_at", OffsetDateTime.class);
                    boolean show = rs.getBoolean("show_activity_status");
                    OffsetDateTime dbNow = rs.getObject("db_now", OffsetDateTime.class);
                    java.time.Instant now = dbNow != null ? dbNow.toInstant() : activityStatusService.now();
                    ActivityStatus status = activityStatusService.resolve(show, lastActiveAt, now);
                    return new ActivityVisibilityResponse(show, status);
                });
    }
}
