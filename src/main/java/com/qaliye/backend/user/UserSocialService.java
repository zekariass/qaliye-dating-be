package com.qaliye.backend.user;

import com.qaliye.backend.user.dto.BlockRequest;
import com.qaliye.backend.user.dto.BlockResponse;
import com.qaliye.backend.user.dto.ReportRequest;
import com.qaliye.backend.user.dto.ReportResponse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class UserSocialService {

    private final NamedParameterJdbcTemplate jdbc;

    public UserSocialService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public ReportResponse reportUser(UUID reporterId, UUID reportedUserId, ReportRequest request) {
        if (reporterId.equals(reportedUserId)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "CANNOT_REPORT_SELF");
        }

        checkTargetExists(reportedUserId);

        UUID reportId = UUID.randomUUID();
        Instant now = Instant.now();

        jdbc.update("""
                INSERT INTO user_reports
                    (id, reporter_user_id, reported_user_id, report_type, description, status)
                VALUES (:id, :reporterId, :reportedUserId, :reportType, :description, 'PENDING')
                """,
                new MapSqlParameterSource()
                        .addValue("id", reportId)
                        .addValue("reporterId", reporterId)
                        .addValue("reportedUserId", reportedUserId)
                        .addValue("reportType", request.reportType())
                        .addValue("description", request.description()));

        return new ReportResponse(reportId, reportedUserId, request.reportType(),
                request.description(), "PENDING", now);
    }

    @Transactional
    public BlockResponse blockUser(UUID blockerId, UUID blockedUserId, BlockRequest request) {
        if (blockerId.equals(blockedUserId)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "CANNOT_BLOCK_SELF");
        }

        checkTargetExists(blockedUserId);

        List<Map<String, Object>> existing = jdbc.queryForList("""
                SELECT id FROM user_blocks
                WHERE blocker_user_id = :blockerId AND blocked_user_id = :blockedUserId
                  AND status = 'ACTIVE'
                """, Map.of("blockerId", blockerId, "blockedUserId", blockedUserId));

        if (!existing.isEmpty()) {
            UUID existingId = (UUID) existing.get(0).get("id");
            return new BlockResponse(existingId, blockedUserId, "ACTIVE",
                    request != null ? request.reason() : null, Instant.now());
        }

        UUID blockId = UUID.randomUUID();
        Instant now = Instant.now();

        jdbc.update("""
                INSERT INTO user_blocks
                    (id, blocker_user_id, blocked_user_id, status, reason)
                VALUES (:id, :blockerId, :blockedUserId, 'ACTIVE', :reason)
                """,
                new MapSqlParameterSource()
                        .addValue("id", blockId)
                        .addValue("blockerId", blockerId)
                        .addValue("blockedUserId", blockedUserId)
                        .addValue("reason", request != null ? request.reason() : null));

        endActiveMatches(blockerId, blockedUserId);

        return new BlockResponse(blockId, blockedUserId, "ACTIVE",
                request != null ? request.reason() : null, now);
    }

    @Transactional
    public void unblockUser(UUID blockerId, UUID blockedUserId) {
        int updated = jdbc.update("""
                UPDATE user_blocks
                SET status = 'REVOKED', revoked_at = NOW(), updated_at = NOW()
                WHERE blocker_user_id = :blockerId AND blocked_user_id = :blockedUserId
                  AND status = 'ACTIVE'
                """, Map.of("blockerId", blockerId, "blockedUserId", blockedUserId));

        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "BLOCK_NOT_FOUND");
        }
    }

    private void checkTargetExists(UUID targetUserId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM app_users WHERE id = :id AND deleted_at IS NULL AND status = 'ACTIVE')",
                Map.of("id", targetUserId), Boolean.class);
        if (!Boolean.TRUE.equals(exists)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        }
    }

    private void endActiveMatches(UUID userId1, UUID userId2) {
        jdbc.update("""
                UPDATE matches
                SET status = 'ENDED', end_reason = 'BLOCKED', ended_at = NOW(), updated_at = NOW()
                WHERE status = 'ACTIVE'
                  AND ((user_one_id = :u1 AND user_two_id = :u2)
                       OR (user_one_id = :u2 AND user_two_id = :u1))
                """, Map.of("u1", userId1, "u2", userId2));
    }
}
