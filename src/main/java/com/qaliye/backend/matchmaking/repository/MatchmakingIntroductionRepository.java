package com.qaliye.backend.matchmaking.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MatchmakingIntroductionRepository {

    public record IntroductionRow(
            UUID id,
            UUID requesterRequestId,
            UUID candidateRequestId,
            UUID initiatedByAdminUserId,
            String requesterDecision,
            String candidateDecision,
            String status,
            int forwardPercentage,
            int reversePercentage,
            int overallPercentage,
            String matchDetails,
            String reason,
            OffsetDateTime requesterDecidedAt,
            OffsetDateTime candidateDecidedAt,
            OffsetDateTime decisionExpiresAt,
            UUID matchId,
            OffsetDateTime proposedAt,
            OffsetDateTime completedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    private static final String SELECT_COLS = """
            id, requester_request_id, candidate_request_id, initiated_by_admin_user_id,
            requester_decision, candidate_decision, status,
            forward_percentage, reverse_percentage, overall_percentage,
            match_details::text, reason,
            requester_decided_at, candidate_decided_at, decision_expires_at,
            match_id, proposed_at, completed_at, created_at, updated_at
            """;

    private static final String INSERT_SQL = """
            INSERT INTO matchmaking_introductions
                (requester_request_id, candidate_request_id, initiated_by_admin_user_id,
                 forward_percentage, reverse_percentage, overall_percentage,
                 match_details, reason, decision_expires_at)
            VALUES
                (:requesterRequestId, :candidateRequestId, :adminUserId,
                 :forwardPercentage, :reversePercentage, :overallPercentage,
                 :matchDetails::jsonb, :reason, :decisionExpiresAt)
            RETURNING id
            """;

    private static final String FIND_BY_ID_SQL = """
            SELECT %s FROM matchmaking_introductions WHERE id = :id
            """.formatted(SELECT_COLS);

    private static final String FIND_BY_ID_FOR_UPDATE_SQL = """
            SELECT %s FROM matchmaking_introductions WHERE id = :id FOR UPDATE
            """.formatted(SELECT_COLS);

    private static final String FIND_ACTIVE_BY_REQUEST_SQL = """
            SELECT %s FROM matchmaking_introductions
            WHERE (requester_request_id = :requestId OR candidate_request_id = :requestId)
              AND status = 'PROPOSED'
            """.formatted(SELECT_COLS);

    private static final String FIND_BY_REQUEST_SQL = """
            SELECT %s FROM matchmaking_introductions
            WHERE requester_request_id = :requestId OR candidate_request_id = :requestId
            ORDER BY created_at DESC
            """.formatted(SELECT_COLS);

    private static final String COUNT_CONSUMED_BY_REQUEST_SQL = """
            SELECT COUNT(*) FROM matchmaking_introductions
            WHERE (requester_request_id = :requestId OR candidate_request_id = :requestId)
              AND status IN ('DECLINED', 'MATCHED', 'EXPIRED')
            """;

    /**
     * Admin dashboard filter fragment — status, a participant's user id
     * (either side), and a display-name substring match on either participant.
     */
    private static final String ADMIN_FILTERS = """
            (:status::text IS NULL OR i.status = :status)
              AND (:userId::uuid IS NULL OR req.user_id = :userId OR cand.user_id = :userId)
              AND (:search::text IS NULL
                   OR LOWER(rp.display_name) LIKE LOWER(:search)
                   OR LOWER(cp.display_name) LIKE LOWER(:search))
            """;

    /** SELECT_COLS with every column qualified by the i. alias (join-safe). */
    private static final String ADMIN_SELECT_COLS = """
            i.id, i.requester_request_id, i.candidate_request_id, i.initiated_by_admin_user_id,
            i.requester_decision, i.candidate_decision, i.status,
            i.forward_percentage, i.reverse_percentage, i.overall_percentage,
            i.match_details::text, i.reason,
            i.requester_decided_at, i.candidate_decided_at, i.decision_expires_at,
            i.match_id, i.proposed_at, i.completed_at, i.created_at, i.updated_at
            """;

    private static final String FIND_ALL_SQL = """
            SELECT %s FROM matchmaking_introductions i
            JOIN matchmaking_requests req  ON req.id  = i.requester_request_id
            JOIN matchmaking_requests cand ON cand.id = i.candidate_request_id
            LEFT JOIN profiles rp ON rp.user_id = req.user_id
            LEFT JOIN profiles cp ON cp.user_id = cand.user_id
            WHERE %s
            ORDER BY i.created_at DESC
            LIMIT :limit OFFSET :offset
            """.formatted(ADMIN_SELECT_COLS, ADMIN_FILTERS);

    private static final String COUNT_ALL_SQL = """
            SELECT COUNT(*) FROM matchmaking_introductions i
            JOIN matchmaking_requests req  ON req.id  = i.requester_request_id
            JOIN matchmaking_requests cand ON cand.id = i.candidate_request_id
            LEFT JOIN profiles rp ON rp.user_id = req.user_id
            LEFT JOIN profiles cp ON cp.user_id = cand.user_id
            WHERE %s
            """.formatted(ADMIN_FILTERS);

    /**
     * Row used by the user-facing introduction history: the introduction
     * fields the summary needs plus both participants' user ids so the
     * service can resolve the caller's side and the partner in one query.
     */
    public record ParticipantRow(
            UUID id,
            UUID requesterRequestId,
            UUID candidateRequestId,
            UUID requesterUserId,
            UUID candidateUserId,
            String requesterDecision,
            String candidateDecision,
            String status,
            int overallPercentage,
            OffsetDateTime decisionExpiresAt,
            UUID matchId,
            OffsetDateTime proposedAt,
            OffsetDateTime createdAt) {}

    private static final String FIND_BY_PARTICIPANT_SQL = """
            SELECT i.id, i.requester_request_id, i.candidate_request_id,
                   req.user_id  AS requester_user_id,
                   cand.user_id AS candidate_user_id,
                   i.requester_decision, i.candidate_decision, i.status,
                   i.overall_percentage, i.decision_expires_at,
                   i.match_id, i.proposed_at, i.created_at
            FROM matchmaking_introductions i
            JOIN matchmaking_requests req  ON req.id  = i.requester_request_id
            JOIN matchmaking_requests cand ON cand.id = i.candidate_request_id
            WHERE req.user_id = :userId OR cand.user_id = :userId
            ORDER BY COALESCE(i.proposed_at, i.created_at) DESC
            LIMIT :limit OFFSET :offset
            """;

    private static final String FIND_EXPIRED_PROPOSED_SQL = """
            SELECT %s FROM matchmaking_introductions
            WHERE status = 'PROPOSED' AND decision_expires_at <= CURRENT_TIMESTAMP
            """.formatted(SELECT_COLS);

    private static final String SET_REQUESTER_DECISION_SQL = """
            UPDATE matchmaking_introductions
            SET requester_decision = :decision,
                requester_decided_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status = 'PROPOSED'
              AND requester_decision = 'PENDING'
            """;

    private static final String SET_CANDIDATE_DECISION_SQL = """
            UPDATE matchmaking_introductions
            SET candidate_decision = :decision,
                candidate_decided_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status = 'PROPOSED'
              AND candidate_decision = 'PENDING'
            """;

    private static final String TRANSITION_STATUS_SQL = """
            UPDATE matchmaking_introductions
            SET status = :newStatus, updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status = :expectedStatus
            """;

    private static final String SET_MATCHED_SQL = """
            UPDATE matchmaking_introductions
            SET status = 'MATCHED', match_id = :matchId,
                completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status = 'PROPOSED'
            """;

    private static final String CANCEL_SQL = """
            UPDATE matchmaking_introductions
            SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status NOT IN ('MATCHED', 'CANCELLED', 'EXPIRED')
            """;

    private static final String EXPIRE_SQL = """
            UPDATE matchmaking_introductions
            SET status = 'EXPIRED', updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status = 'PROPOSED'
            """;

    private static final String FIND_RECENTLY_INTRODUCED_PAIR_SQL = """
            SELECT EXISTS (
                SELECT 1 FROM matchmaking_introductions
                WHERE (
                    (requester_request_id = :reqA AND candidate_request_id = :reqB)
                    OR (requester_request_id = :reqB AND candidate_request_id = :reqA)
                )
                AND created_at >= CURRENT_TIMESTAMP - INTERVAL '1 day' * :cooldownDays
            )
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public MatchmakingIntroductionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(UUID requesterRequestId, UUID candidateRequestId, UUID adminUserId,
                       int forwardPercentage, int reversePercentage, int overallPercentage,
                       String matchDetails, String reason, OffsetDateTime decisionExpiresAt) {
        var params = new MapSqlParameterSource()
                .addValue("requesterRequestId", requesterRequestId)
                .addValue("candidateRequestId", candidateRequestId)
                .addValue("adminUserId", adminUserId)
                .addValue("forwardPercentage", forwardPercentage)
                .addValue("reversePercentage", reversePercentage)
                .addValue("overallPercentage", overallPercentage)
                .addValue("matchDetails", matchDetails)
                .addValue("reason", reason)
                .addValue("decisionExpiresAt", decisionExpiresAt);
        return jdbc.query(INSERT_SQL, params, (rs, i) -> rs.getObject("id", UUID.class))
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Failed to insert introduction"));
    }

    public Optional<IntroductionRow> findById(UUID id) {
        return jdbc.query(FIND_BY_ID_SQL, new MapSqlParameterSource("id", id), this::mapRow)
                .stream().findFirst();
    }

    public Optional<IntroductionRow> findByIdForUpdate(UUID id) {
        return jdbc.query(FIND_BY_ID_FOR_UPDATE_SQL, new MapSqlParameterSource("id", id), this::mapRow)
                .stream().findFirst();
    }

    public Optional<IntroductionRow> findActiveByRequestId(UUID requestId) {
        return jdbc.query(FIND_ACTIVE_BY_REQUEST_SQL, new MapSqlParameterSource("requestId", requestId), this::mapRow)
                .stream().findFirst();
    }

    public List<IntroductionRow> findByRequestId(UUID requestId) {
        return jdbc.query(FIND_BY_REQUEST_SQL, new MapSqlParameterSource("requestId", requestId), this::mapRow);
    }

    /**
     * Counts introductions that consumed quota for a given request:
     * DECLINED, MATCHED, and EXPIRED count; CANCELLED does not.
     */
    public int countConsumedByRequest(UUID requestId) {
        Long count = jdbc.queryForObject(COUNT_CONSUMED_BY_REQUEST_SQL,
                new MapSqlParameterSource("requestId", requestId), Long.class);
        return count == null ? 0 : count.intValue();
    }

    /**
     * Admin dashboard list: newest first. All filters optional —
     * {@code userId} matches either participant, {@code search} is a
     * display-name substring match on either participant.
     */
    public List<IntroductionRow> findAll(String status, UUID userId, String search,
                                         int page, int size) {
        var params = new MapSqlParameterSource()
                .addValue("status", status, Types.VARCHAR)
                .addValue("userId", userId, Types.OTHER)
                .addValue("search", search, Types.VARCHAR)
                .addValue("limit", size)
                .addValue("offset", page * size);
        return jdbc.query(FIND_ALL_SQL, params, this::mapRow);
    }

    public long countAll(String status, UUID userId, String search) {
        var params = new MapSqlParameterSource()
                .addValue("status", status, Types.VARCHAR)
                .addValue("userId", userId, Types.OTHER)
                .addValue("search", search, Types.VARCHAR);
        Long total = jdbc.queryForObject(COUNT_ALL_SQL, params, Long.class);
        return total != null ? total : 0;
    }

    /**
     * Caller's introduction history (either role), newest proposal first.
     */
    public List<ParticipantRow> findByParticipantUserId(UUID userId, int page, int size) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("limit", size)
                .addValue("offset", page * size);
        return jdbc.query(FIND_BY_PARTICIPANT_SQL, params, (rs, i) -> new ParticipantRow(
                rs.getObject("id", UUID.class),
                rs.getObject("requester_request_id", UUID.class),
                rs.getObject("candidate_request_id", UUID.class),
                rs.getObject("requester_user_id", UUID.class),
                rs.getObject("candidate_user_id", UUID.class),
                rs.getString("requester_decision"),
                rs.getString("candidate_decision"),
                rs.getString("status"),
                rs.getInt("overall_percentage"),
                rs.getObject("decision_expires_at", OffsetDateTime.class),
                rs.getObject("match_id", UUID.class),
                rs.getObject("proposed_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class)));
    }

    public List<IntroductionRow> findExpiredProposed() {
        return jdbc.query(FIND_EXPIRED_PROPOSED_SQL, new MapSqlParameterSource(), this::mapRow);
    }

    public boolean setRequesterDecision(UUID id, String decision) {
        var params = new MapSqlParameterSource("id", id).addValue("decision", decision);
        return jdbc.update(SET_REQUESTER_DECISION_SQL, params) > 0;
    }

    public boolean setCandidateDecision(UUID id, String decision) {
        var params = new MapSqlParameterSource("id", id).addValue("decision", decision);
        return jdbc.update(SET_CANDIDATE_DECISION_SQL, params) > 0;
    }

    public boolean setMatched(UUID id, UUID matchId) {
        var params = new MapSqlParameterSource("id", id).addValue("matchId", matchId);
        return jdbc.update(SET_MATCHED_SQL, params) > 0;
    }

    public boolean transitionStatus(UUID id, String expectedStatus, String newStatus) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("expectedStatus", expectedStatus)
                .addValue("newStatus", newStatus);
        return jdbc.update(TRANSITION_STATUS_SQL, params) > 0;
    }

    public boolean cancel(UUID id) {
        return jdbc.update(CANCEL_SQL, new MapSqlParameterSource("id", id)) > 0;
    }

    public boolean expire(UUID id) {
        return jdbc.update(EXPIRE_SQL, new MapSqlParameterSource("id", id)) > 0;
    }

    public boolean wasRecentlyIntroduced(UUID requestA, UUID requestB, int cooldownDays) {
        var params = new MapSqlParameterSource()
                .addValue("reqA", requestA)
                .addValue("reqB", requestB)
                .addValue("cooldownDays", cooldownDays);
        Boolean result = jdbc.queryForObject(FIND_RECENTLY_INTRODUCED_PAIR_SQL, params, Boolean.class);
        return Boolean.TRUE.equals(result);
    }

    // ── Mappers ────────────────────────────────────────────────────────────

    private IntroductionRow mapRow(ResultSet rs, int i) throws SQLException {
        return new IntroductionRow(
                rs.getObject("id", UUID.class),
                rs.getObject("requester_request_id", UUID.class),
                rs.getObject("candidate_request_id", UUID.class),
                rs.getObject("initiated_by_admin_user_id", UUID.class),
                rs.getString("requester_decision"),
                rs.getString("candidate_decision"),
                rs.getString("status"),
                rs.getInt("forward_percentage"),
                rs.getInt("reverse_percentage"),
                rs.getInt("overall_percentage"),
                rs.getString("match_details"),
                rs.getString("reason"),
                rs.getObject("requester_decided_at", OffsetDateTime.class),
                rs.getObject("candidate_decided_at", OffsetDateTime.class),
                rs.getObject("decision_expires_at", OffsetDateTime.class),
                rs.getObject("match_id", UUID.class),
                rs.getObject("proposed_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}
