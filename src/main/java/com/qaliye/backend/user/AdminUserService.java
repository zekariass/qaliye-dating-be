package com.qaliye.backend.user;

import com.qaliye.backend.user.dto.AdminUserDetailDto;
import com.qaliye.backend.user.dto.AdminUserSummaryDto;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AdminUserService {

    private static final String LIST_USERS_SQL = """
            SELECT u.id, u.status, u.role, u.preferred_language, u.last_active_at, u.created_at,
                   p.display_name, p.is_onboarded, p.is_verified, p.profile_completion_score
            FROM app_users u
            LEFT JOIN profiles p ON p.user_id = u.id
            WHERE (:status IS NULL OR u.status = :status)
              AND (:role IS NULL OR u.role = :role)
              AND (:search IS NULL OR LOWER(p.display_name) LIKE LOWER(:search))
            ORDER BY u.created_at DESC
            LIMIT :pageSize OFFSET :offset
            """;

    private static final String COUNT_USERS_SQL = """
            SELECT COUNT(*)
            FROM app_users u
            LEFT JOIN profiles p ON p.user_id = u.id
            WHERE (:status IS NULL OR u.status = :status)
              AND (:role IS NULL OR u.role = :role)
              AND (:search IS NULL OR LOWER(p.display_name) LIKE LOWER(:search))
            """;

    private static final String USER_DETAIL_SQL = """
            SELECT u.id, u.status, u.role, u.preferred_language,
                   u.last_active_at, u.deleted_at, u.created_at, u.updated_at,
                   p.display_name, p.gender, p.residency_type, p.relationship_intention,
                   p.is_onboarded, p.is_verified, p.is_visible, p.profile_completion_score
            FROM app_users u
            LEFT JOIN profiles p ON p.user_id = u.id
            WHERE u.id = :userId
            """;

    private static final String PHOTO_STATS_SQL = """
            SELECT
                COUNT(*) AS total,
                COUNT(*) FILTER (WHERE moderation_status = 'PENDING') AS pending,
                COUNT(*) FILTER (WHERE moderation_status = 'APPROVED') AS approved,
                COUNT(*) FILTER (WHERE moderation_status = 'REJECTED') AS rejected,
                COUNT(*) FILTER (WHERE moderation_status = 'MANUAL_REVIEW') AS manual_review
            FROM profile_photos
            WHERE user_id = :userId AND deleted_at IS NULL
            """;

    private static final String REPORT_STATS_SQL = """
            SELECT
                COUNT(*) AS total,
                COUNT(*) FILTER (WHERE status = 'PENDING') AS pending
            FROM user_reports
            WHERE reported_user_id = :userId
            """;

    private static final String VERIFICATION_STATUS_SQL = """
            SELECT status FROM user_verifications
            WHERE user_id = :userId
            ORDER BY submitted_at DESC
            LIMIT 1
            """;

    private static final String ACTIVE_MATCH_COUNT_SQL = """
            SELECT COUNT(*) FROM matches
            WHERE (user_one_id = :userId OR user_two_id = :userId)
              AND status = 'ACTIVE'
            """;

    private static final String UPDATE_STATUS_SQL = """
            UPDATE app_users SET status = :status, updated_at = NOW()
            WHERE id = :userId AND status != :status
            RETURNING id
            """;

    private static final String UPDATE_ROLE_SQL = """
            UPDATE app_users SET role = :role, updated_at = NOW()
            WHERE id = :userId AND role != :role
            RETURNING id
            """;

    private static final String AUDIT_LOG_SQL = """
            INSERT INTO audit_log (actor_user_id, action, target_table, target_id, details)
            VALUES (:actorId, :action, :targetTable, :targetId, :details::jsonb)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final UserStatusService userStatusService;
    private final CacheManager cacheManager;

    public AdminUserService(NamedParameterJdbcTemplate jdbc,
                            UserStatusService userStatusService,
                            CacheManager cacheManager) {
        this.jdbc = jdbc;
        this.userStatusService = userStatusService;
        this.cacheManager = cacheManager;
    }

    public Map<String, Object> listUsers(String status, String role, String search,
                                          int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        String searchPattern = search != null && !search.isBlank() ? "%" + search.trim() + "%" : null;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("status", status != null && !status.isBlank() ? status : null, Types.VARCHAR)
                .addValue("role", role != null && !role.isBlank() ? role : null, Types.VARCHAR)
                .addValue("search", searchPattern, Types.VARCHAR)
                .addValue("pageSize", pageSize)
                .addValue("offset", offset);

        List<AdminUserSummaryDto> users = jdbc.query(LIST_USERS_SQL, params, (rs, n) -> {
            String displayName = rs.getString("display_name");
            return new AdminUserSummaryDto(
                    rs.getObject("id", UUID.class),
                    displayName,
                    rs.getString("status"),
                    rs.getString("role"),
                    rs.getString("preferred_language"),
                    rs.getBoolean("is_onboarded"),
                    rs.getBoolean("is_verified"),
                    rs.getInt("profile_completion_score"),
                    toOffsetDateTime(rs.getTimestamp("last_active_at")),
                    toOffsetDateTime(rs.getTimestamp("created_at"))
            );
        });

        Long total = jdbc.queryForObject(COUNT_USERS_SQL, params, Long.class);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("users", users);
        result.put("total", total != null ? total : 0);
        result.put("page", page);
        result.put("pageSize", pageSize);
        return result;
    }

    public AdminUserDetailDto getUserDetail(UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(USER_DETAIL_SQL,
                Map.of("userId", userId));
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user_not_found");
        }
        Map<String, Object> row = rows.get(0);

        Map<String, Object> photoStats = jdbc.queryForList(PHOTO_STATS_SQL,
                Map.of("userId", userId)).get(0);
        Map<String, Object> reportStats = jdbc.queryForList(REPORT_STATS_SQL,
                Map.of("userId", userId)).get(0);

        String verificationStatus = jdbc.query(VERIFICATION_STATUS_SQL,
                Map.of("userId", userId),
                (rs, n) -> rs.getString("status")).stream().findFirst().orElse(null);

        Long activeMatchCount = jdbc.queryForObject(ACTIVE_MATCH_COUNT_SQL,
                Map.of("userId", userId), Long.class);

        return new AdminUserDetailDto(
                (UUID) row.get("id"),
                (String) row.get("display_name"),
                (String) row.get("status"),
                (String) row.get("role"),
                (String) row.get("preferred_language"),
                (String) row.get("gender"),
                (String) row.get("residency_type"),
                (String) row.get("relationship_intention"),
                (Boolean) row.get("is_onboarded"),
                (Boolean) row.get("is_verified"),
                (Boolean) row.get("is_visible"),
                row.get("profile_completion_score") != null ? ((Number) row.get("profile_completion_score")).intValue() : 0,
                ((Number) photoStats.get("total")).intValue(),
                ((Number) photoStats.get("pending")).intValue(),
                ((Number) photoStats.get("approved")).intValue(),
                ((Number) photoStats.get("rejected")).intValue(),
                ((Number) photoStats.get("manual_review")).intValue(),
                ((Number) reportStats.get("total")).intValue(),
                ((Number) reportStats.get("pending")).intValue(),
                verificationStatus,
                activeMatchCount != null ? activeMatchCount.intValue() : 0,
                toOffsetDateTime((Timestamp) row.get("last_active_at")),
                toOffsetDateTime((Timestamp) row.get("deleted_at")),
                toOffsetDateTime((Timestamp) row.get("created_at")),
                toOffsetDateTime((Timestamp) row.get("updated_at"))
        );
    }

    @Transactional
    public void updateUserStatus(UUID adminId, UUID userId, String newStatus, String reason) {
        requireAdmin(adminId);
        if (adminId.equals(userId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cannot_change_own_status");
        }

        List<UUID> updated = jdbc.query(UPDATE_STATUS_SQL,
                new MapSqlParameterSource()
                        .addValue("status", newStatus)
                        .addValue("userId", userId),
                (rs, n) -> rs.getObject("id", UUID.class));

        if (updated.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user_not_found_or_same_status");
        }

        evictUserStatusCache(userId);

        String details = "{\"status\": \"" + newStatus + "\""
                + (reason != null && !reason.isBlank() ? ", \"reason\": \"" + escapeJson(reason) + "\"" : "")
                + "}";
        writeAuditLog(adminId, "USER_STATUS_CHANGED", "app_users", userId, details);
    }

    @Transactional
    public void updateUserRole(UUID adminId, UUID userId, String newRole) {
        requireAdmin(adminId);
        if (adminId.equals(userId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cannot_change_own_role");
        }

        List<UUID> updated = jdbc.query(UPDATE_ROLE_SQL,
                new MapSqlParameterSource()
                        .addValue("role", newRole)
                        .addValue("userId", userId),
                (rs, n) -> rs.getObject("id", UUID.class));

        if (updated.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user_not_found_or_same_role");
        }

        evictUserStatusCache(userId);

        String details = "{\"role\": \"" + newRole + "\"}";
        writeAuditLog(adminId, "USER_ROLE_CHANGED", "app_users", userId, details);
    }

    private void requireAdmin(UUID callerId) {
        UserStatusService.UserStatus status = userStatusService.getStatus(callerId);
        if (status == null || !"ADMIN".equals(status.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin_access_required");
        }
    }

    private void evictUserStatusCache(UUID userId) {
        Cache cache = cacheManager.getCache("userStatus");
        if (cache != null) {
            cache.evict(userId);
        }
    }

    private void writeAuditLog(UUID actorId, String action, String targetTable,
                               UUID targetId, String details) {
        jdbc.update(AUDIT_LOG_SQL, new MapSqlParameterSource()
                .addValue("actorId", actorId)
                .addValue("action", action)
                .addValue("targetTable", targetTable)
                .addValue("targetId", targetId)
                .addValue("details", details));
    }

    private OffsetDateTime toOffsetDateTime(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
