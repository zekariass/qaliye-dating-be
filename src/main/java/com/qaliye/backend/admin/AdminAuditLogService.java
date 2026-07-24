package com.qaliye.backend.admin;

import com.qaliye.backend.admin.dto.AuditLogEntryDto;
import com.qaliye.backend.user.UserStatusService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AdminAuditLogService {

    private static final String LIST_AUDIT_SQL = """
            SELECT al.id, al.actor_user_id, al.action, al.target_table, al.target_id,
                   al.request_id, al.details, al.created_at,
                   p.display_name AS actor_display_name
            FROM audit_log al
            LEFT JOIN profiles p ON p.user_id = al.actor_user_id
            WHERE (CAST(:action AS text) IS NULL OR al.action = CAST(:action AS text))
              AND (CAST(:targetTable AS text) IS NULL OR al.target_table = CAST(:targetTable AS text))
              AND (CAST(:actorId AS uuid) IS NULL OR al.actor_user_id = CAST(:actorId AS uuid))
              AND (CAST(:targetId AS uuid) IS NULL OR al.target_id = CAST(:targetId AS uuid))
            ORDER BY al.created_at DESC
            LIMIT :pageSize OFFSET :offset
            """;

    private static final String COUNT_AUDIT_SQL = """
            SELECT COUNT(*)
            FROM audit_log al
            WHERE (CAST(:action AS text) IS NULL OR al.action = CAST(:action AS text))
              AND (CAST(:targetTable AS text) IS NULL OR al.target_table = CAST(:targetTable AS text))
              AND (CAST(:actorId AS uuid) IS NULL OR al.actor_user_id = CAST(:actorId AS uuid))
              AND (CAST(:targetId AS uuid) IS NULL OR al.target_id = CAST(:targetId AS uuid))
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final UserStatusService userStatusService;

    public AdminAuditLogService(NamedParameterJdbcTemplate jdbc,
                                UserStatusService userStatusService) {
        this.jdbc = jdbc;
        this.userStatusService = userStatusService;
    }

    public Map<String, Object> listAuditLog(UUID callerId, String action, String targetTable,
                                             UUID actorId, UUID targetId,
                                             int page, int pageSize) {
        requireAdmin(callerId);

        int offset = (page - 1) * pageSize;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("action", action != null && !action.isBlank() ? action : null)
                .addValue("targetTable", targetTable != null && !targetTable.isBlank() ? targetTable : null)
                .addValue("actorId", actorId)
                .addValue("targetId", targetId)
                .addValue("pageSize", pageSize)
                .addValue("offset", offset);

        List<AuditLogEntryDto> entries = jdbc.query(LIST_AUDIT_SQL, params, (rs, n) -> {
            UUID actorUserId = rs.getObject("actor_user_id", UUID.class);
            String actorDisplayName = rs.getString("actor_display_name");
            String details = rs.getString("details");
            return new AuditLogEntryDto(
                    rs.getObject("id", UUID.class),
                    actorUserId,
                    actorDisplayName,
                    rs.getString("action"),
                    rs.getString("target_table"),
                    rs.getObject("target_id", UUID.class),
                    rs.getObject("request_id", UUID.class),
                    details,
                    toOffsetDateTime(rs.getTimestamp("created_at"))
            );
        });

        Long total = jdbc.queryForObject(COUNT_AUDIT_SQL, params, Long.class);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entries", entries);
        result.put("total", total != null ? total : 0);
        result.put("page", page);
        result.put("pageSize", pageSize);
        return result;
    }

    private void requireAdmin(UUID callerId) {
        UserStatusService.UserStatus status = userStatusService.getStatus(callerId);
        if (status == null || !"ADMIN".equals(status.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin_access_required");
        }
    }

    private OffsetDateTime toOffsetDateTime(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }
}
