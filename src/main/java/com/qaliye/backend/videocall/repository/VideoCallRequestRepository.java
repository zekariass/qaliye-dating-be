package com.qaliye.backend.videocall.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@code video_call_requests} — one live request per match
 * (partial unique index) enforced by guarded transitions everywhere.
 */
@Repository
public class VideoCallRequestRepository {

    public record VideoCallRequestRow(
            UUID id,
            UUID matchId,
            UUID requesterUserId,
            UUID responderUserId,
            String status,
            String callType,
            OffsetDateTime requestExpiresAt,
            OffsetDateTime respondedAt,
            String channelName,
            UUID requesterChargeKey,
            OffsetDateTime requesterChargedAt,
            OffsetDateTime requesterJoinedAt,
            OffsetDateTime responderJoinedAt,
            OffsetDateTime callDeadlineAt,
            OffsetDateTime endedAt,
            int reminderCount,
            OffsetDateTime lastRemindedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    /** Minimal match-participant shape for access checks. */
    public record MatchPair(UUID matchId, UUID userOneId, UUID userTwoId, String status) {}

    private static final String SELECT_COLS = """
            id, match_id, requester_user_id, responder_user_id, status,
            call_type,
            request_expires_at, responded_at, channel_name,
            requester_charge_key, requester_charged_at,
            requester_joined_at, responder_joined_at,
            call_deadline_at,
            ended_at, reminder_count, last_reminded_at,
            created_at, updated_at
            """;

    private static final String INSERT_SQL = """
            INSERT INTO video_call_requests
                (match_id, requester_user_id, responder_user_id, status, call_type,
                 request_expires_at)
            VALUES (:matchId, :requesterId, :responderId, 'PENDING', :callType,
                    :requestExpiresAt)
            RETURNING id
            """;

    private static final String FIND_BY_ID_SQL = """
            SELECT %s FROM video_call_requests WHERE id = :id
            """.formatted(SELECT_COLS);

    private static final String LOCK_BY_ID_SQL = """
            SELECT %s FROM video_call_requests WHERE id = :id FOR UPDATE
            """.formatted(SELECT_COLS);

    private static final String FIND_ACTIVE_BY_MATCH_SQL = """
            SELECT %s FROM video_call_requests
            WHERE match_id = :matchId AND status IN ('PENDING', 'ACCEPTED')
            """.formatted(SELECT_COLS);

    private static final String FIND_BY_MATCH_SQL = """
            SELECT %s FROM video_call_requests
            WHERE match_id = :matchId
            ORDER BY created_at DESC
            LIMIT :size OFFSET :offset
            """.formatted(SELECT_COLS);

    private static final String COUNT_BY_MATCH_SQL = """
            SELECT COUNT(*) FROM video_call_requests WHERE match_id = :matchId
            """;

    private static final String ACCEPT_SQL = """
            UPDATE video_call_requests
            SET status = 'ACCEPTED',
                channel_name = :channelName,
                responded_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status = 'PENDING'
            """;

    private static final String DECLINE_SQL = """
            UPDATE video_call_requests
            SET status = 'DECLINED',
                responded_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status = 'PENDING'
            """;

    private static final String CANCEL_SQL = """
            UPDATE video_call_requests
            SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status = 'PENDING'
            """;

    private static final String CANCEL_ACTIVE_SQL = """
            UPDATE video_call_requests
            SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status IN ('PENDING', 'ACCEPTED')
            """;

    /**
     * Expiry — PENDING rows past request_expires_at, or ACCEPTED rows that
     * hit it while NEITHER participant ever joined (the long both-absent
     * window). The join predicates are re-evaluated after waiting on a
     * join's row lock, so a last-instant join wins the race.
     */
    private static final String EXPIRE_SQL = """
            UPDATE video_call_requests
            SET status = 'EXPIRED', updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
              AND (status = 'PENDING'
                   OR (status = 'ACCEPTED'
                       AND requester_joined_at IS NULL
                       AND responder_joined_at IS NULL))
            """;

    private static final String STAMP_REQUESTER_JOIN_SQL = """
            UPDATE video_call_requests
            SET requester_joined_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status = 'ACCEPTED' AND requester_joined_at IS NULL
            """;

    private static final String STAMP_RESPONDER_JOIN_SQL = """
            UPDATE video_call_requests
            SET responder_joined_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status = 'ACCEPTED' AND responder_joined_at IS NULL
            """;

    /**
     * Anchors the per-call deadline on the FIRST join of either party —
     * guarded so a second joiner never moves the deadline. Under the row
     * lock exactly one join wins; retries/rejoins keep the original anchor.
     */
    private static final String ANCHOR_DEADLINE_SQL = """
            UPDATE video_call_requests
            SET call_deadline_at = :deadline, updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status = 'ACCEPTED' AND call_deadline_at IS NULL
            """;

    /**
     * Deadline hit → COMPLETED with ended_at stamped at the deadline itself
     * (accurate end time regardless of sweeper lag). Guarded to ACCEPTED so
     * the sweeper and a post-deadline join can't double-transition.
     */
    private static final String COMPLETE_TIME_LIMIT_SQL = """
            UPDATE video_call_requests
            SET status = 'COMPLETED',
                ended_at = call_deadline_at,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status = 'ACCEPTED'
            """;

    /**
     * Idempotent charge stamp — UNIQUE(requester_charge_key) makes exactly
     * one caller win. The loser re-reads and sees chargeKey non-NULL → no
     * second charge (invariant §15.2).
     */
    private static final String STAMP_CHARGE_SQL = """
            UPDATE video_call_requests
            SET requester_charge_key = :chargeKey,
                requester_charged_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND requester_charge_key IS NULL
            """;

    /**
     * Cooldown-guarded reminder stamp — succeeds only while PENDING and the
     * cooldown window has elapsed (or never reminded). Single statement, so
     * concurrent reminders can't both pass.
     */
    private static final String REMIND_SQL = """
            UPDATE video_call_requests
            SET reminder_count = reminder_count + 1,
                last_reminded_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status = 'PENDING'
              AND (last_reminded_at IS NULL
                   OR last_reminded_at <= CURRENT_TIMESTAMP
                          - (:cooldownSeconds * INTERVAL '1 second'))
            RETURNING reminder_count
            """;

    private static final String END_SQL = """
            UPDATE video_call_requests
            SET status = 'COMPLETED',
                ended_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status = 'ACCEPTED'
            """;

    private static final String FIND_EXPIRED_SQL = """
            SELECT %s FROM video_call_requests
            WHERE request_expires_at < CURRENT_TIMESTAMP
              AND (status = 'PENDING'
                   OR (status = 'ACCEPTED'
                       AND requester_joined_at IS NULL
                       AND responder_joined_at IS NULL))
            """.formatted(SELECT_COLS);

    private static final String FIND_DEADLINE_PASSED_SQL = """
            SELECT %s FROM video_call_requests
            WHERE status = 'ACCEPTED'
              AND call_deadline_at IS NOT NULL
              AND call_deadline_at < CURRENT_TIMESTAMP
            """.formatted(SELECT_COLS);

    /**
     * ACCEPTED calls where EXACTLY ONE participant joined and has been
     * waiting alone longer than the join grace — the other party gets
     * graceSeconds measured from that first join, so a late join (the
     * request can live up to request_expires_at while nobody has joined)
     * still earns a full grace window instead of an instant close.
     * Both-missing rows are not here — they live until request_expires_at
     * and expire via FIND_EXPIRED_SQL.
     */
    private static final String FIND_NO_SHOW_SQL = """
            SELECT %s FROM video_call_requests
            WHERE status = 'ACCEPTED'
              AND (requester_joined_at IS NULL)
                     <> (responder_joined_at IS NULL)
              AND COALESCE(requester_joined_at, responder_joined_at)
                     <= CURRENT_TIMESTAMP
                        - (:graceSeconds * INTERVAL '1 second')
            """.formatted(SELECT_COLS);

    /**
     * No-show close — guarded to ACCEPTED so a racing join/transition can't
     * double-close, and the join-stamp predicate is re-evaluated after
     * waiting on a join's row lock: if the missing participant joined while
     * the sweeper was blocked, this update matches nothing and the join wins.
     */
    private static final String MARK_NO_SHOW_SQL = """
            UPDATE video_call_requests
            SET status = 'NO_SHOW',
                ended_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status = 'ACCEPTED'
              AND (requester_joined_at IS NULL OR responder_joined_at IS NULL)
            """;

    private static final String CANCEL_ACTIVE_FOR_MATCH_SQL = """
            UPDATE video_call_requests
            SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP
            WHERE match_id = :matchId AND status IN ('PENDING', 'ACCEPTED')
            """;

    private static final String FIND_MATCH_SQL = """
            SELECT id, user_one_id, user_two_id, status
            FROM matches WHERE id = :matchId
            """;

    private static final String BLOCK_EXISTS_SQL = """
            SELECT COUNT(*) FROM user_blocks
            WHERE status = 'ACTIVE'
              AND (
                    (blocker_user_id = :u1 AND blocked_user_id = :u2)
                 OR (blocker_user_id = :u2 AND blocked_user_id = :u1)
              )
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public VideoCallRequestRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(UUID matchId, UUID requesterUserId, UUID responderUserId,
                       String callType, OffsetDateTime requestExpiresAt) {
        var params = new MapSqlParameterSource()
                .addValue("matchId", matchId)
                .addValue("requesterId", requesterUserId)
                .addValue("responderId", responderUserId)
                .addValue("callType", callType)
                .addValue("requestExpiresAt", requestExpiresAt);
        return jdbc.query(INSERT_SQL, params,
                        (rs, i) -> rs.getObject("id", UUID.class))
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Failed to insert video-call request"));
    }

    public Optional<VideoCallRequestRow> findById(UUID id) {
        return jdbc.query(FIND_BY_ID_SQL, new MapSqlParameterSource("id", id), this::mapRow)
                .stream().findFirst();
    }

    public Optional<VideoCallRequestRow> lockById(UUID id) {
        return jdbc.query(LOCK_BY_ID_SQL, new MapSqlParameterSource("id", id), this::mapRow)
                .stream().findFirst();
    }

    public Optional<VideoCallRequestRow> findActiveByMatchId(UUID matchId) {
        return jdbc.query(FIND_ACTIVE_BY_MATCH_SQL,
                new MapSqlParameterSource("matchId", matchId), this::mapRow)
                .stream().findFirst();
    }

    public List<VideoCallRequestRow> findByMatchId(UUID matchId, int page, int size) {
        var params = new MapSqlParameterSource()
                .addValue("matchId", matchId)
                .addValue("size", size)
                .addValue("offset", page * size);
        return jdbc.query(FIND_BY_MATCH_SQL, params, this::mapRow);
    }

    public long countByMatchId(UUID matchId) {
        Long n = jdbc.queryForObject(COUNT_BY_MATCH_SQL,
                new MapSqlParameterSource("matchId", matchId), Long.class);
        return n != null ? n : 0;
    }

    public boolean accept(UUID id, String channelName) {
        return jdbc.update(ACCEPT_SQL,
                new MapSqlParameterSource("id", id).addValue("channelName", channelName)) > 0;
    }

    public boolean decline(UUID id) {
        return jdbc.update(DECLINE_SQL, new MapSqlParameterSource("id", id)) > 0;
    }

    public boolean cancel(UUID id) {
        return jdbc.update(CANCEL_SQL, new MapSqlParameterSource("id", id)) > 0;
    }

    /** Cancels PENDING or ACCEPTED — used by lazy block/match-inactive checks. */
    public boolean cancelActive(UUID id) {
        return jdbc.update(CANCEL_ACTIVE_SQL, new MapSqlParameterSource("id", id)) > 0;
    }

    public boolean expire(UUID id) {
        return jdbc.update(EXPIRE_SQL, new MapSqlParameterSource("id", id)) > 0;
    }

    public boolean stampRequesterJoin(UUID id) {
        return jdbc.update(STAMP_REQUESTER_JOIN_SQL, new MapSqlParameterSource("id", id)) > 0;
    }

    public boolean stampResponderJoin(UUID id) {
        return jdbc.update(STAMP_RESPONDER_JOIN_SQL, new MapSqlParameterSource("id", id)) > 0;
    }

    /** Wins exactly once per request; returns false when already stamped. */
    public boolean stampCharge(UUID id, UUID chargeKey) {
        var params = new MapSqlParameterSource("id", id).addValue("chargeKey", chargeKey);
        return jdbc.update(STAMP_CHARGE_SQL, params) > 0;
    }

    public boolean end(UUID id) {
        return jdbc.update(END_SQL, new MapSqlParameterSource("id", id)) > 0;
    }

    /** Wins exactly once; returns false when the deadline was already set. */
    public boolean anchorDeadline(UUID id, OffsetDateTime deadline) {
        var params = new MapSqlParameterSource("id", id).addValue("deadline", deadline);
        return jdbc.update(ANCHOR_DEADLINE_SQL, params) > 0;
    }

    /** Ends an ACCEPTED call at its deadline; false if already terminal. */
    public boolean completeTimeLimit(UUID id) {
        return jdbc.update(COMPLETE_TIME_LIMIT_SQL, new MapSqlParameterSource("id", id)) > 0;
    }

    /**
     * Stamps a reminder when the cooldown elapsed; returns the new
     * {@code reminder_count}, or {@code null} when the guard rejected it
     * (cooldown still running or request not PENDING).
     */
    public Integer tryReminder(UUID id, int cooldownSeconds) {
        var params = new MapSqlParameterSource("id", id)
                .addValue("cooldownSeconds", cooldownSeconds);
        List<Integer> rows = jdbc.query(REMIND_SQL, params,
                (rs, n) -> rs.getInt("reminder_count"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Expirable rows: PENDING past the request window, or ACCEPTED past it
     * with neither participant ever joined (sweeper input).
     */
    public List<VideoCallRequestRow> findExpired() {
        return jdbc.query(FIND_EXPIRED_SQL, new MapSqlParameterSource(), this::mapRow);
    }

    /** ACCEPTED calls whose anchored deadline has elapsed (sweeper input). */
    public List<VideoCallRequestRow> findDeadlinePassed() {
        return jdbc.query(FIND_DEADLINE_PASSED_SQL, new MapSqlParameterSource(), this::mapRow);
    }

    /**
     * ACCEPTED calls past {@code graceSeconds} since accept where at least
     * one participant never joined (no-show sweeper input).
     */
    public List<VideoCallRequestRow> findNoShowCandidates(int graceSeconds) {
        return jdbc.query(FIND_NO_SHOW_SQL,
                new MapSqlParameterSource("graceSeconds", graceSeconds), this::mapRow);
    }

    /** Closes an ACCEPTED call as NO_SHOW; false if already transitioned. */
    public boolean markNoShow(UUID id) {
        return jdbc.update(MARK_NO_SHOW_SQL, new MapSqlParameterSource("id", id)) > 0;
    }

    /** Cancels every live request for a match (unmatch / block / end). */
    public int cancelActiveForMatch(UUID matchId) {
        return jdbc.update(CANCEL_ACTIVE_FOR_MATCH_SQL,
                new MapSqlParameterSource("matchId", matchId));
    }

    // ── Match / block lookups ────────────────────────────────────────────

    public Optional<MatchPair> findMatch(UUID matchId) {
        return jdbc.query(FIND_MATCH_SQL,
                        new MapSqlParameterSource("matchId", matchId),
                        (rs, i) -> new MatchPair(
                                rs.getObject("id", UUID.class),
                                rs.getObject("user_one_id", UUID.class),
                                rs.getObject("user_two_id", UUID.class),
                                rs.getString("status")))
                .stream().findFirst();
    }

    public boolean blockExists(UUID userA, UUID userB) {
        Long n = jdbc.queryForObject(BLOCK_EXISTS_SQL,
                new MapSqlParameterSource("u1", userA).addValue("u2", userB), Long.class);
        return n != null && n > 0;
    }

    // ── Mapper ────────────────────────────────────────────────────────────

    private VideoCallRequestRow mapRow(ResultSet rs, int i) throws SQLException {
        return new VideoCallRequestRow(
                rs.getObject("id", UUID.class),
                rs.getObject("match_id", UUID.class),
                rs.getObject("requester_user_id", UUID.class),
                rs.getObject("responder_user_id", UUID.class),
                rs.getString("status"),
                rs.getString("call_type"),
                rs.getObject("request_expires_at", OffsetDateTime.class),
                rs.getObject("responded_at", OffsetDateTime.class),
                rs.getString("channel_name"),
                rs.getObject("requester_charge_key", UUID.class),
                rs.getObject("requester_charged_at", OffsetDateTime.class),
                rs.getObject("requester_joined_at", OffsetDateTime.class),
                rs.getObject("responder_joined_at", OffsetDateTime.class),
                rs.getObject("call_deadline_at", OffsetDateTime.class),
                rs.getObject("ended_at", OffsetDateTime.class),
                rs.getInt("reminder_count"),
                rs.getObject("last_reminded_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}
