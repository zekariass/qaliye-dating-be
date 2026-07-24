package com.qaliye.backend.admin;

import com.qaliye.backend.user.AccountDeletionService;
import com.qaliye.backend.user.UserStatusService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class AdminAccountDeletionService {

    private static final String CHECK_USER_SQL = """
            SELECT status FROM app_users WHERE id = :userId
            """;

    private static final String AUDIT_LOG_SQL = """
            INSERT INTO audit_log (actor_user_id, action, target_table, target_id, details)
            VALUES (:actorId, :action, :targetTable, :targetId, :details::jsonb)
            """;

    private final AccountDeletionService accountDeletionService;
    private final UserStatusService userStatusService;
    private final NamedParameterJdbcTemplate jdbc;

    public AdminAccountDeletionService(AccountDeletionService accountDeletionService,
                                       UserStatusService userStatusService,
                                       NamedParameterJdbcTemplate jdbc) {
        this.accountDeletionService = accountDeletionService;
        this.userStatusService = userStatusService;
        this.jdbc = jdbc;
    }

    public void deleteAccount(UUID adminId, UUID targetUserId, String reason) {
        requireAdmin(adminId);
        if (adminId.equals(targetUserId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cannot_delete_own_account");
        }

        // Verify target exists
        var rows = jdbc.queryForList(CHECK_USER_SQL, java.util.Map.of("userId", targetUserId));
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user_not_found");
        }

        // AccountDeletionService.deleteAccount is idempotent — safe to call even if already DELETED
        accountDeletionService.deleteAccount(targetUserId);

        String details = "{\"reason\": \"" + escapeJson(reason != null ? reason : "admin_initiated") + "\"}";
        writeAuditLog(adminId, "ADMIN_ACCOUNT_DELETED", "app_users", targetUserId, details);
    }

    private void requireAdmin(UUID callerId) {
        UserStatusService.UserStatus status = userStatusService.getStatus(callerId);
        if (status == null || !"ADMIN".equals(status.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin_access_required");
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

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
