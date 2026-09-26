package com.qaliye.backend.blinddate.repository;

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
 * Sessions, rounds and session-question snapshots.
 */
@Repository
public class BlindDateSessionRepository {

    public record SessionRow(UUID id, UUID creatorUserId, String status, String languageCode,
                             OffsetDateTime expiresAt, OffsetDateTime closedAt,
                             OffsetDateTime completedAt, OffsetDateTime createdAt) {}

    public record RoundRow(UUID id, UUID sessionId, int roundNumber, String status,
                           OffsetDateTime createdAt, OffsetDateTime startedAt,
                           OffsetDateTime completedAt) {}

    public record SessionQuestionRow(UUID id, UUID sessionId, UUID roundId,
                                     UUID sourceQuestionId, UUID sourceCustomQuestionId,
                                     String questionText, String answerText,
                                     String languageCode, int sortOrder) {}

    /** A discoverable session plus its current still-active participant count. */
    public record DiscoverableSessionRow(SessionRow session, int participantCount) {}

    /** A session the caller created or joined, with their participant row if any. */
    public record MySessionRow(SessionRow session, UUID participantId, String participantStatus,
                               UUID currentRoundId, int participantCount, int currentRoundNumber,
                               int pendingQuestionCount) {}

    /**
     * Public creator profile info shown to participants so they can decide
     * whether to join. Photo is returned as raw storage coordinates; the
     * service layer signs it.
     */
    public record CreatorInfoRow(UUID userId, String displayName, String gender,
                                 String religion, String relationshipIntention, Integer age,
                                 String city, String countryName,
                                 UUID photoId, String photoBucket, String photoPath) {}

    // ── Sessions ───────────────────────────────────────────────────────────

    private static final String INSERT_SESSION_SQL = """
            INSERT INTO blind_date_sessions
                (creator_user_id, status, language_code, expires_at, credit_charge_idempotency_key)
            VALUES (:creatorUserId, 'OPEN', :languageCode, :expiresAt, :idempotencyKey)
            RETURNING id
            """;

    private static final String FIND_SESSION_BY_ID_SQL = """
            SELECT id, creator_user_id, status, language_code, expires_at, closed_at, completed_at, created_at
            FROM blind_date_sessions
            WHERE id = :sessionId
            """;

    private static final String FIND_SESSION_BY_ID_FOR_UPDATE_SQL = """
            SELECT id, creator_user_id, status, language_code, expires_at, closed_at, completed_at, created_at
            FROM blind_date_sessions
            WHERE id = :sessionId
            FOR UPDATE
            """;

    private static final String FIND_ACTIVE_SESSION_BY_IDEMPOTENCY_KEY_SQL = """
            SELECT id, creator_user_id, status, language_code, expires_at, closed_at, completed_at, created_at
            FROM blind_date_sessions
            WHERE credit_charge_idempotency_key = :idempotencyKey
            """;

    private static final String CLOSE_SESSION_SQL = """
            UPDATE blind_date_sessions
            SET status = 'CLOSED', closed_at = NOW(), updated_at = NOW()
            WHERE id = :sessionId AND status = 'OPEN'
            """;

    private static final String TRANSITION_TO_REVEAL_SQL = """
            UPDATE blind_date_sessions
            SET status = 'REVEAL', updated_at = NOW()
            WHERE id = :sessionId AND status = 'OPEN'
            """;

    private static final String COMPLETE_SESSION_SQL = """
            UPDATE blind_date_sessions
            SET status = 'COMPLETED', completed_at = NOW(), updated_at = NOW()
            WHERE id = :sessionId AND status = 'REVEAL'
            """;

    private static final String EXPIRE_OPEN_SESSIONS_SQL = """
            UPDATE blind_date_sessions
            SET status = 'EXPIRED', updated_at = NOW()
            WHERE status = 'OPEN'
              AND expires_at IS NOT NULL
              AND expires_at <= NOW()
            RETURNING id
            """;

    private static final String CANCEL_SESSION_SQL = """
            UPDATE blind_date_sessions
            SET status = 'CANCELLED', closed_at = NOW(), updated_at = NOW()
            WHERE id = :sessionId
              AND status IN ('OPEN', 'REVEAL')
            """;

    private static final String FIND_DISCOVERABLE_SESSIONS_SQL = """
            SELECT s.id, s.creator_user_id, s.status, s.language_code,
                   s.expires_at, s.closed_at, s.completed_at, s.created_at,
                   (SELECT COUNT(1) FROM blind_date_session_participants p
                    WHERE p.session_id = s.id
                      AND p.status IN ('ACTIVE', 'ADVANCED', 'FINALIST', 'REVEALED')
                   ) AS participant_count
            FROM blind_date_sessions s
            JOIN blind_date_session_rounds r
                 ON r.session_id = s.id AND r.round_number = 1 AND r.status = 'OPEN'
            JOIN profiles creatorProfile ON creatorProfile.user_id = s.creator_user_id
            JOIN profiles callerProfile ON callerProfile.user_id = :callerId
            JOIN discovery_preferences creatorPrefs ON creatorPrefs.user_id = s.creator_user_id
            JOIN discovery_preferences callerPrefs ON callerPrefs.user_id = :callerId
            WHERE s.status = 'OPEN'
              AND s.creator_user_id <> :callerId
              AND (s.expires_at IS NULL OR s.expires_at > NOW())
              AND NOT EXISTS (
                  SELECT 1 FROM blind_date_session_participants p
                  WHERE p.session_id = s.id AND p.user_id = :callerId
              )
              AND NOT EXISTS (
                  SELECT 1 FROM user_blocks ub
                  WHERE ub.status = 'ACTIVE'
                    AND (
                        (ub.blocker_user_id = s.creator_user_id AND ub.blocked_user_id = :callerId)
                        OR (ub.blocker_user_id = :callerId AND ub.blocked_user_id = s.creator_user_id)
                    )
              )
              AND NOT EXISTS (
                  SELECT 1 FROM matches m
                  WHERE m.status = 'ACTIVE'
                    AND ((m.user_one_id = s.creator_user_id AND m.user_two_id = :callerId)
                      OR (m.user_one_id = :callerId AND m.user_two_id = s.creator_user_id))
              )
              AND creatorPrefs.interested_in_gender = callerProfile.gender
              AND callerPrefs.interested_in_gender = creatorProfile.gender
              AND calculate_age(callerProfile.date_of_birth)
                    BETWEEN creatorPrefs.min_age AND COALESCE(creatorPrefs.max_age, 120)
              AND calculate_age(creatorProfile.date_of_birth)
                    BETWEEN callerPrefs.min_age AND COALESCE(callerPrefs.max_age, 120)
            ORDER BY s.created_at DESC
            LIMIT :limit OFFSET :offset
            """;

    private static final String FIND_MY_SESSIONS_SQL = """
            SELECT s.id, s.creator_user_id, s.status, s.language_code,
                   s.expires_at, s.closed_at, s.completed_at, s.created_at,
                   p.id AS participant_id, p.status AS participant_status, p.current_round_id,
                   (SELECT COUNT(1) FROM blind_date_session_participants sp
                    WHERE sp.session_id = s.id
                      AND sp.status IN ('ACTIVE', 'ADVANCED', 'FINALIST', 'REVEALED')
                   ) AS participant_count,
                   (SELECT COALESCE(MAX(r2.round_number), 0) FROM blind_date_session_rounds r2
                    WHERE r2.session_id = s.id
                   ) AS current_round_number,
                   (SELECT COUNT(1) FROM blind_date_session_questions q
                    JOIN blind_date_session_rounds r3 ON r3.id = q.round_id
                    WHERE q.round_id = p.current_round_id
                      AND r3.status = 'OPEN'
                      AND NOT EXISTS (SELECT 1 FROM blind_date_session_answers a
                                      WHERE a.participant_id = p.id
                                        AND a.session_question_id = q.id)
                   ) AS pending_question_count
            FROM blind_date_sessions s
            LEFT JOIN blind_date_session_participants p
                   ON p.session_id = s.id AND p.user_id = :callerId
            WHERE s.creator_user_id = :callerId OR p.id IS NOT NULL
            ORDER BY s.created_at DESC
            LIMIT :limit OFFSET :offset
            """;

    private static final String FIND_MY_PARTICIPATIONS_SQL = """
            SELECT s.id, s.creator_user_id, s.status, s.language_code,
                   s.expires_at, s.closed_at, s.completed_at, s.created_at,
                   p.id AS participant_id, p.status AS participant_status, p.current_round_id,
                   (SELECT COUNT(1) FROM blind_date_session_participants sp
                    WHERE sp.session_id = s.id
                      AND sp.status IN ('ACTIVE', 'ADVANCED', 'FINALIST', 'REVEALED')
                   ) AS participant_count,
                   (SELECT COALESCE(MAX(r2.round_number), 0) FROM blind_date_session_rounds r2
                    WHERE r2.session_id = s.id
                   ) AS current_round_number,
                   (SELECT COUNT(1) FROM blind_date_session_questions q
                    JOIN blind_date_session_rounds r3 ON r3.id = q.round_id
                    WHERE q.round_id = p.current_round_id
                      AND r3.status = 'OPEN'
                      AND NOT EXISTS (SELECT 1 FROM blind_date_session_answers a
                                      WHERE a.participant_id = p.id
                                        AND a.session_question_id = q.id)
                   ) AS pending_question_count
            FROM blind_date_session_participants p
            JOIN blind_date_sessions s ON s.id = p.session_id
            WHERE p.user_id = :callerId
            ORDER BY p.joined_at DESC
            LIMIT :limit OFFSET :offset
            """;

    // ── Rounds ─────────────────────────────────────────────────────────────

    private static final String INSERT_ROUND_SQL = """
            INSERT INTO blind_date_session_rounds (session_id, round_number, status, started_at)
            VALUES (:sessionId, :roundNumber, 'OPEN', NOW())
            RETURNING id
            """;

    private static final String CLOSE_ROUND_SQL = """
            UPDATE blind_date_session_rounds
            SET status = 'CLOSED', completed_at = NOW()
            WHERE id = :roundId AND status = 'OPEN'
            """;

    private static final String CLOSE_OPEN_ROUNDS_FOR_SESSION_SQL = """
            UPDATE blind_date_session_rounds
            SET status = 'CLOSED', completed_at = NOW()
            WHERE session_id = :sessionId AND status = 'OPEN'
            """;

    private static final String FIND_ROUND_BY_ID_SQL = """
            SELECT id, session_id, round_number, status, created_at, started_at, completed_at
            FROM blind_date_session_rounds
            WHERE id = :roundId
            """;

    private static final String FIND_ROUND_BY_ID_FOR_UPDATE_SQL = """
            SELECT id, session_id, round_number, status, created_at, started_at, completed_at
            FROM blind_date_session_rounds
            WHERE id = :roundId
            FOR UPDATE
            """;

    private static final String FIND_OPEN_ROUND_SQL = """
            SELECT id, session_id, round_number, status, created_at, started_at, completed_at
            FROM blind_date_session_rounds
            WHERE session_id = :sessionId AND status = 'OPEN'
            """;

    private static final String FIND_ROUNDS_FOR_SESSION_SQL = """
            SELECT id, session_id, round_number, status, created_at, started_at, completed_at
            FROM blind_date_session_rounds
            WHERE session_id = :sessionId
            ORDER BY round_number
            """;

    private static final String FIND_LATEST_ROUND_NUMBER_SQL = """
            SELECT COALESCE(MAX(round_number), 0) FROM blind_date_session_rounds
            WHERE session_id = :sessionId
            """;

    // ── Session question snapshots ────────────────────────────────────────

    private static final String INSERT_SESSION_QUESTION_SQL = """
            INSERT INTO blind_date_session_questions
                (session_id, round_id, source_question_id, source_custom_question_id,
                 question_text, answer_text, language_code, sort_order)
            VALUES (:sessionId, :roundId, :sourceQuestionId, :sourceCustomQuestionId,
                    :questionText, :answerText, :languageCode, :sortOrder)
            RETURNING id
            """;

    private static final String FIND_QUESTIONS_FOR_ROUND_SQL = """
            SELECT id, session_id, round_id, source_question_id, source_custom_question_id,
                   question_text, answer_text, language_code, sort_order
            FROM blind_date_session_questions
            WHERE round_id = :roundId
            ORDER BY sort_order
            """;

    private static final String FIND_SESSION_QUESTION_BY_ID_SQL = """
            SELECT id, session_id, round_id, source_question_id, source_custom_question_id,
                   question_text, answer_text, language_code, sort_order
            FROM blind_date_session_questions
            WHERE id = :sessionQuestionId
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public BlindDateSessionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Sessions ───────────────────────────────────────────────────────────

    public UUID insertSession(UUID creatorUserId, String languageCode,
                              OffsetDateTime expiresAt, UUID idempotencyKey) {
        var params = new MapSqlParameterSource()
                .addValue("creatorUserId", creatorUserId)
                .addValue("languageCode", languageCode)
                .addValue("expiresAt", expiresAt)
                .addValue("idempotencyKey", idempotencyKey);
        return jdbc.query(INSERT_SESSION_SQL, params, (rs, i) -> rs.getObject("id", UUID.class))
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Failed to create Blind Date session"));
    }

    public Optional<SessionRow> findSession(UUID sessionId) {
        return jdbc.query(FIND_SESSION_BY_ID_SQL, new MapSqlParameterSource("sessionId", sessionId),
                        this::mapSession)
                .stream().findFirst();
    }

    public Optional<SessionRow> findSessionForUpdate(UUID sessionId) {
        return jdbc.query(FIND_SESSION_BY_ID_FOR_UPDATE_SQL, new MapSqlParameterSource("sessionId", sessionId),
                        this::mapSession)
                .stream().findFirst();
    }

    public Optional<SessionRow> findByIdempotencyKey(UUID idempotencyKey) {
        return jdbc.query(FIND_ACTIVE_SESSION_BY_IDEMPOTENCY_KEY_SQL,
                        new MapSqlParameterSource("idempotencyKey", idempotencyKey), this::mapSession)
                .stream().findFirst();
    }

    public boolean closeSession(UUID sessionId) {
        return jdbc.update(CLOSE_SESSION_SQL, new MapSqlParameterSource("sessionId", sessionId)) > 0;
    }

    public boolean transitionToReveal(UUID sessionId) {
        return jdbc.update(TRANSITION_TO_REVEAL_SQL, new MapSqlParameterSource("sessionId", sessionId)) > 0;
    }

    public boolean completeSession(UUID sessionId) {
        return jdbc.update(COMPLETE_SESSION_SQL, new MapSqlParameterSource("sessionId", sessionId)) > 0;
    }

    public List<UUID> expireOpenSessions() {
        return jdbc.query(EXPIRE_OPEN_SESSIONS_SQL, new MapSqlParameterSource(),
                (rs, i) -> rs.getObject("id", UUID.class));
    }

    public boolean cancelSession(UUID sessionId) {
        return jdbc.update(CANCEL_SESSION_SQL, new MapSqlParameterSource("sessionId", sessionId)) > 0;
    }

    public List<DiscoverableSessionRow> findDiscoverableSessions(UUID callerId, int page, int size) {
        var params = new MapSqlParameterSource()
                .addValue("callerId", callerId)
                .addValue("limit", size)
                .addValue("offset", page * size);
        return jdbc.query(FIND_DISCOVERABLE_SESSIONS_SQL, params, (rs, i) ->
                new DiscoverableSessionRow(mapSession(rs, i), rs.getInt("participant_count")));
    }

    public List<MySessionRow> findMySessions(UUID callerId, int page, int size) {
        var params = new MapSqlParameterSource()
                .addValue("callerId", callerId)
                .addValue("limit", size)
                .addValue("offset", page * size);
        return jdbc.query(FIND_MY_SESSIONS_SQL, params, (rs, i) -> new MySessionRow(
                mapSession(rs, i),
                rs.getObject("participant_id", UUID.class),
                rs.getString("participant_status"),
                rs.getObject("current_round_id", UUID.class),
                rs.getInt("participant_count"),
                rs.getInt("current_round_number"),
                rs.getInt("pending_question_count")));
    }

    public List<MySessionRow> findMyParticipations(UUID callerId, int page, int size) {
        var params = new MapSqlParameterSource()
                .addValue("callerId", callerId)
                .addValue("limit", size)
                .addValue("offset", page * size);
        return jdbc.query(FIND_MY_PARTICIPATIONS_SQL, params, (rs, i) -> new MySessionRow(
                mapSession(rs, i),
                rs.getObject("participant_id", UUID.class),
                rs.getString("participant_status"),
                rs.getObject("current_round_id", UUID.class),
                rs.getInt("participant_count"),
                rs.getInt("current_round_number"),
                rs.getInt("pending_question_count")));
    }

    // ── Rounds ─────────────────────────────────────────────────────────────

    public UUID insertRound(UUID sessionId, int roundNumber) {
        var params = new MapSqlParameterSource()
                .addValue("sessionId", sessionId)
                .addValue("roundNumber", roundNumber);
        return jdbc.query(INSERT_ROUND_SQL, params, (rs, i) -> rs.getObject("id", UUID.class))
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Failed to create Blind Date round"));
    }

    public boolean closeRound(UUID roundId) {
        return jdbc.update(CLOSE_ROUND_SQL, new MapSqlParameterSource("roundId", roundId)) > 0;
    }

    public int closeOpenRoundsForSession(UUID sessionId) {
        return jdbc.update(CLOSE_OPEN_ROUNDS_FOR_SESSION_SQL, new MapSqlParameterSource("sessionId", sessionId));
    }

    public Optional<RoundRow> findRound(UUID roundId) {
        return jdbc.query(FIND_ROUND_BY_ID_SQL, new MapSqlParameterSource("roundId", roundId), this::mapRound)
                .stream().findFirst();
    }

    public Optional<RoundRow> findRoundForUpdate(UUID roundId) {
        return jdbc.query(FIND_ROUND_BY_ID_FOR_UPDATE_SQL, new MapSqlParameterSource("roundId", roundId), this::mapRound)
                .stream().findFirst();
    }

    public Optional<RoundRow> findOpenRound(UUID sessionId) {
        return jdbc.query(FIND_OPEN_ROUND_SQL, new MapSqlParameterSource("sessionId", sessionId), this::mapRound)
                .stream().findFirst();
    }

    public List<RoundRow> findRoundsForSession(UUID sessionId) {
        return jdbc.query(FIND_ROUNDS_FOR_SESSION_SQL, new MapSqlParameterSource("sessionId", sessionId), this::mapRound);
    }

    public int findLatestRoundNumber(UUID sessionId) {
        Integer max = jdbc.queryForObject(FIND_LATEST_ROUND_NUMBER_SQL,
                new MapSqlParameterSource("sessionId", sessionId), Integer.class);
        return max != null ? max : 0;
    }

    // ── Creator info ───────────────────────────────────────────────────────

    private static final String FIND_CREATOR_INFO_SQL = """
            SELECT p.user_id, p.display_name, p.gender, p.religion, p.relationship_intention,
                   calculate_age(p.date_of_birth) AS age,
                   a.city, a.country_name,
                   pp.id AS photo_id, pp.storage_bucket, pp.storage_path
            FROM profiles p
            LEFT JOIN app_users au ON au.id = p.user_id
            LEFT JOIN addresses a  ON a.id  = au.address_id
            LEFT JOIN profile_photos pp ON pp.user_id = p.user_id
                   AND pp.is_primary         = TRUE
                   AND pp.moderation_status  = 'APPROVED'
                   AND pp.deleted_at         IS NULL
            WHERE p.user_id = ANY(:userIds::UUID[])
            """;

    /** Batch-loads public creator info for the given user ids. */
    public List<CreatorInfoRow> findCreatorInfo(java.util.Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        String idsParam = userIds.stream().map(UUID::toString)
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        return jdbc.query(FIND_CREATOR_INFO_SQL,
                new MapSqlParameterSource("userIds", idsParam),
                (rs, i) -> new CreatorInfoRow(
                        rs.getObject("user_id", UUID.class),
                        rs.getString("display_name"),
                        rs.getString("gender"),
                        rs.getString("religion"),
                        rs.getString("relationship_intention"),
                        rs.getObject("age", Integer.class),
                        rs.getString("city"),
                        rs.getString("country_name"),
                        rs.getObject("photo_id", UUID.class),
                        rs.getString("storage_bucket"),
                        rs.getString("storage_path")));
    }

    // ── Session question snapshots ────────────────────────────────────────

    public UUID insertSessionQuestion(UUID sessionId, UUID roundId, UUID sourceQuestionId,
                                      UUID sourceCustomQuestionId, String questionText,
                                      String answerText, String languageCode, int sortOrder) {
        var params = new MapSqlParameterSource()
                .addValue("sessionId", sessionId)
                .addValue("roundId", roundId)
                .addValue("sourceQuestionId", sourceQuestionId)
                .addValue("sourceCustomQuestionId", sourceCustomQuestionId)
                .addValue("questionText", questionText)
                .addValue("answerText", answerText)
                .addValue("languageCode", languageCode)
                .addValue("sortOrder", sortOrder);
        return jdbc.query(INSERT_SESSION_QUESTION_SQL, params, (rs, i) -> rs.getObject("id", UUID.class))
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Failed to snapshot Blind Date question"));
    }

    public List<SessionQuestionRow> findQuestionsForRound(UUID roundId) {
        return jdbc.query(FIND_QUESTIONS_FOR_ROUND_SQL, new MapSqlParameterSource("roundId", roundId),
                this::mapSessionQuestion);
    }

    public Optional<SessionQuestionRow> findSessionQuestion(UUID sessionQuestionId) {
        return jdbc.query(FIND_SESSION_QUESTION_BY_ID_SQL,
                        new MapSqlParameterSource("sessionQuestionId", sessionQuestionId), this::mapSessionQuestion)
                .stream().findFirst();
    }

    // ── Mappers ────────────────────────────────────────────────────────────

    private SessionRow mapSession(ResultSet rs, int rowNum) throws SQLException {
        return new SessionRow(
                rs.getObject("id", UUID.class),
                rs.getObject("creator_user_id", UUID.class),
                rs.getString("status"),
                rs.getString("language_code"),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("closed_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private RoundRow mapRound(ResultSet rs, int rowNum) throws SQLException {
        return new RoundRow(
                rs.getObject("id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getInt("round_number"),
                rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class));
    }

    private SessionQuestionRow mapSessionQuestion(ResultSet rs, int rowNum) throws SQLException {
        return new SessionQuestionRow(
                rs.getObject("id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getObject("round_id", UUID.class),
                rs.getObject("source_question_id", UUID.class),
                rs.getObject("source_custom_question_id", UUID.class),
                rs.getString("question_text"),
                rs.getString("answer_text"),
                rs.getString("language_code"),
                rs.getInt("sort_order"));
    }
}
