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
public class MatchmakingRequestRepository {

    public record RequestRow(
            UUID id,
            UUID userId,
            UUID matchmakingPreferencesId,
            String status,
            UUID requestChargeIdempotencyKey,
            String contactPhone,
            OffsetDateTime createdAt,
            OffsetDateTime completedAt,
            OffsetDateTime cancelledAt,
            OffsetDateTime expiresAt,
            OffsetDateTime updatedAt,
            /** Live (PROPOSED) introduction involving this request, if any. */
            UUID activeIntroductionId) {}

    private static final String SELECT_COLS = """
            id, user_id, matchmaking_preferences_id, status,
            request_charge_idempotency_key, contact_phone,
            created_at, completed_at, cancelled_at, expires_at, updated_at,
            (SELECT i.id FROM matchmaking_introductions i
             WHERE (i.requester_request_id = matchmaking_requests.id
                 OR i.candidate_request_id = matchmaking_requests.id)
               AND i.status = 'PROPOSED'
             LIMIT 1) AS active_introduction_id
            """;

    private static final String INSERT_SQL = """
            INSERT INTO matchmaking_requests
                (user_id, matchmaking_preferences_id, status,
                 request_charge_idempotency_key, expires_at)
            VALUES
                (:userId, :preferencesId, 'OPEN', :chargeKey, :expiresAt)
            RETURNING id
            """;

    private static final String FIND_BY_ID_SQL = """
            SELECT %s FROM matchmaking_requests WHERE id = :id
            """.formatted(SELECT_COLS);

    private static final String FIND_BY_ID_FOR_UPDATE_SQL = """
            SELECT %s FROM matchmaking_requests WHERE id = :id FOR UPDATE
            """.formatted(SELECT_COLS);

    private static final String FIND_ACTIVE_BY_USER_SQL = """
            SELECT %s FROM matchmaking_requests
            WHERE user_id = :userId AND status IN ('OPEN', 'ON_HOLD')
            """.formatted(SELECT_COLS);

    private static final String FIND_BY_CHARGE_KEY_SQL = """
            SELECT %s FROM matchmaking_requests
            WHERE request_charge_idempotency_key = :chargeKey
            """.formatted(SELECT_COLS);

    private static final String FIND_BY_USER_SQL = """
            SELECT %s FROM matchmaking_requests
            WHERE user_id = :userId
            ORDER BY created_at DESC
            LIMIT :limit OFFSET :offset
            """.formatted(SELECT_COLS);

    private static final String FIND_QUEUE_SQL = """
            SELECT %s FROM matchmaking_requests
            WHERE (:status::text IS NULL OR status = :status)
            ORDER BY created_at ASC
            LIMIT :limit OFFSET :offset
            """.formatted(SELECT_COLS);

    private static final String COUNT_QUEUE_SQL = """
            SELECT COUNT(*) FROM matchmaking_requests
            WHERE (:status::text IS NULL OR status = :status)
            """;

    private static final String UPDATE_STATUS_SQL = """
            UPDATE matchmaking_requests
            SET status = :status, updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status = :expectedStatus
            """;

    private static final String CANCEL_SQL = """
            UPDATE matchmaking_requests
            SET status = 'CANCELLED', cancelled_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status IN ('OPEN', 'ON_HOLD')
            """;

    private static final String EXPIRE_OPEN_SQL = """
            UPDATE matchmaking_requests
            SET status = 'EXPIRED', updated_at = CURRENT_TIMESTAMP
            WHERE status = 'OPEN' AND expires_at <= CURRENT_TIMESTAMP
            RETURNING id
            """;

    private static final String RELEASE_ON_HOLD_SQL = """
            UPDATE matchmaking_requests
            SET status = 'OPEN', updated_at = CURRENT_TIMESTAMP
            WHERE id IN (:idA, :idB) AND status = 'ON_HOLD'
            """;

    private static final String SET_COMPLETED_SQL = """
            UPDATE matchmaking_requests
            SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE id IN (:idA, :idB) AND status = 'ON_HOLD'
            """;

    private static final String SET_MATCHED_SQL = """
            UPDATE matchmaking_requests
            SET status = 'MATCHED', updated_at = CURRENT_TIMESTAMP
            WHERE id IN (:idA, :idB)
            """;

    private static final String SET_ON_HOLD_SQL = """
            UPDATE matchmaking_requests
            SET status = 'ON_HOLD', updated_at = CURRENT_TIMESTAMP
            WHERE id IN (:idA, :idB) AND status = 'OPEN'
            """;

    private static final String COMPLETE_SINGLE_SQL = """
            UPDATE matchmaking_requests
            SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status = 'ON_HOLD'
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public MatchmakingRequestRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(UUID userId, UUID preferencesId, UUID chargeKey, OffsetDateTime expiresAt) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("preferencesId", preferencesId)
                .addValue("chargeKey", chargeKey)
                .addValue("expiresAt", expiresAt);
        return jdbc.query(INSERT_SQL, params, (rs, i) -> rs.getObject("id", UUID.class))
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Failed to insert matchmaking request"));
    }

    public Optional<RequestRow> findById(UUID id) {
        return jdbc.query(FIND_BY_ID_SQL, new MapSqlParameterSource("id", id), this::mapRow)
                .stream().findFirst();
    }

    public Optional<RequestRow> findByIdForUpdate(UUID id) {
        return jdbc.query(FIND_BY_ID_FOR_UPDATE_SQL, new MapSqlParameterSource("id", id), this::mapRow)
                .stream().findFirst();
    }

    public Optional<RequestRow> findActiveByUserId(UUID userId) {
        return jdbc.query(FIND_ACTIVE_BY_USER_SQL, new MapSqlParameterSource("userId", userId), this::mapRow)
                .stream().findFirst();
    }

    public Optional<RequestRow> findByChargeKey(UUID chargeKey) {
        return jdbc.query(FIND_BY_CHARGE_KEY_SQL, new MapSqlParameterSource("chargeKey", chargeKey), this::mapRow)
                .stream().findFirst();
    }

    public List<RequestRow> findByUserId(UUID userId, int page, int size) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("limit", size)
                .addValue("offset", page * size);
        return jdbc.query(FIND_BY_USER_SQL, params, this::mapRow);
    }

    /** Admin queue: all requests when status is null, else a single status. */
    public List<RequestRow> findQueue(String status, int page, int size) {
        var params = new MapSqlParameterSource()
                .addValue("status", status, Types.VARCHAR)
                .addValue("limit", size)
                .addValue("offset", page * size);
        return jdbc.query(FIND_QUEUE_SQL, params, this::mapRow);
    }

    public long countQueue(String status) {
        var params = new MapSqlParameterSource().addValue("status", status, Types.VARCHAR);
        Long total = jdbc.queryForObject(COUNT_QUEUE_SQL, params, Long.class);
        return total != null ? total : 0;
    }

    public boolean updateStatus(UUID id, String expectedStatus, String newStatus) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("expectedStatus", expectedStatus)
                .addValue("status", newStatus);
        return jdbc.update(UPDATE_STATUS_SQL, params) > 0;
    }

    public boolean cancel(UUID id) {
        return jdbc.update(CANCEL_SQL, new MapSqlParameterSource("id", id)) > 0;
    }

    public List<UUID> expireOpenRequests() {
        return jdbc.query(EXPIRE_OPEN_SQL, new MapSqlParameterSource(),
                (rs, i) -> rs.getObject("id", UUID.class));
    }

    public int setOnHold(UUID idA, UUID idB) {
        return jdbc.update(SET_ON_HOLD_SQL,
                new MapSqlParameterSource("idA", idA).addValue("idB", idB));
    }

    public int releaseOnHold(UUID idA, UUID idB) {
        return jdbc.update(RELEASE_ON_HOLD_SQL,
                new MapSqlParameterSource("idA", idA).addValue("idB", idB));
    }

    public int setCompleted(UUID idA, UUID idB) {
        return jdbc.update(SET_COMPLETED_SQL,
                new MapSqlParameterSource("idA", idA).addValue("idB", idB));
    }

    public int setMatched(UUID idA, UUID idB) {
        return jdbc.update(SET_MATCHED_SQL,
                new MapSqlParameterSource("idA", idA).addValue("idB", idB));
    }

    /** Quota-exhausted settle: transitions a single ON_HOLD request to COMPLETED. */
    public boolean complete(UUID id) {
        return jdbc.update(COMPLETE_SINGLE_SQL, new MapSqlParameterSource("id", id)) > 0;
    }

    // ── Mappers ────────────────────────────────────────────────────────────

    private RequestRow mapRow(ResultSet rs, int i) throws SQLException {
        return new RequestRow(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("matchmaking_preferences_id", UUID.class),
                rs.getString("status"),
                rs.getObject("request_charge_idempotency_key", UUID.class),
                rs.getString("contact_phone"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class),
                rs.getObject("cancelled_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getObject("active_introduction_id", UUID.class));
    }
}
