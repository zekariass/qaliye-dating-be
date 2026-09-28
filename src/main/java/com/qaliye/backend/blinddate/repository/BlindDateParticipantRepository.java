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

@Repository
public class BlindDateParticipantRepository {

    public record ParticipantRow(UUID id, UUID sessionId, UUID userId, String status,
                                 UUID currentRoundId, OffsetDateTime joinedAt,
                                 OffsetDateTime eliminatedAt, OffsetDateTime advancedAt,
                                 OffsetDateTime finalistAt, OffsetDateTime withdrawnAt,
                                 OffsetDateTime revealedAt) {}

    public record AnswerRow(UUID id, UUID participantId, UUID sessionQuestionId,
                            String answer, OffsetDateTime submittedAt) {}

    public record SelectionRow(UUID id, UUID roundId, UUID participantId,
                               UUID selectedByUserId, String decision, OffsetDateTime createdAt) {}

    /** A participant's answer joined with the snapshotted question text. */
    public record ParticipantAnswerView(UUID sessionQuestionId, String questionText,
                                        String answer, OffsetDateTime submittedAt) {}

    // ── Participants ───────────────────────────────────────────────────────

    private static final String INSERT_PARTICIPANT_SQL = """
            INSERT INTO blind_date_session_participants
                (session_id, user_id, status, current_round_id, credit_charge_idempotency_key)
            VALUES (:sessionId, :userId, 'ACTIVE', :roundId, :idempotencyKey)
            RETURNING id
            """;

    private static final String FIND_PARTICIPANT_BY_ID_SQL = """
            SELECT id, session_id, user_id, status, current_round_id, joined_at,
                   eliminated_at, advanced_at, finalist_at, withdrawn_at, revealed_at
            FROM blind_date_session_participants
            WHERE id = :participantId
            """;

    private static final String FIND_PARTICIPANT_BY_ID_FOR_UPDATE_SQL = """
            SELECT id, session_id, user_id, status, current_round_id, joined_at,
                   eliminated_at, advanced_at, finalist_at, withdrawn_at, revealed_at
            FROM blind_date_session_participants
            WHERE id = :participantId
            FOR UPDATE
            """;

    private static final String FIND_PARTICIPANT_BY_SESSION_AND_USER_SQL = """
            SELECT id, session_id, user_id, status, current_round_id, joined_at,
                   eliminated_at, advanced_at, finalist_at, withdrawn_at, revealed_at
            FROM blind_date_session_participants
            WHERE session_id = :sessionId AND user_id = :userId
            """;

    private static final String FIND_BY_IDEMPOTENCY_KEY_SQL = """
            SELECT id, session_id, user_id, status, current_round_id, joined_at,
                   eliminated_at, advanced_at, finalist_at, withdrawn_at, revealed_at
            FROM blind_date_session_participants
            WHERE credit_charge_idempotency_key = :idempotencyKey
            """;

    private static final String FIND_PARTICIPANTS_FOR_SESSION_SQL = """
            SELECT id, session_id, user_id, status, current_round_id, joined_at,
                   eliminated_at, advanced_at, finalist_at, withdrawn_at, revealed_at
            FROM blind_date_session_participants
            WHERE session_id = :sessionId
            ORDER BY joined_at
            """;

    private static final String FIND_ACTIVE_PARTICIPANTS_IN_ROUND_SQL = """
            SELECT id, session_id, user_id, status, current_round_id, joined_at,
                   eliminated_at, advanced_at, finalist_at, withdrawn_at, revealed_at
            FROM blind_date_session_participants
            WHERE current_round_id = :roundId
              AND status IN ('ACTIVE', 'ADVANCED')
            ORDER BY joined_at
            """;

    private static final String FIND_STILL_ACTIVE_IN_SESSION_SQL = """
            SELECT id, session_id, user_id, status, current_round_id, joined_at,
                   eliminated_at, advanced_at, finalist_at, withdrawn_at, revealed_at
            FROM blind_date_session_participants
            WHERE session_id = :sessionId
              AND status IN ('ACTIVE', 'ADVANCED')
            """;

    private static final String ADVANCE_PARTICIPANT_SQL = """
            UPDATE blind_date_session_participants
            SET status = 'ADVANCED', advanced_at = NOW(), current_round_id = :nextRoundId, updated_at = NOW()
            WHERE id = :participantId
            """;

    private static final String ELIMINATE_PARTICIPANT_SQL = """
            UPDATE blind_date_session_participants
            SET status = 'ELIMINATED', eliminated_at = NOW(), updated_at = NOW()
            WHERE id = :participantId
              AND status IN ('ACTIVE', 'ADVANCED')
            """;

    private static final String ELIMINATE_ALL_STILL_ACTIVE_EXCEPT_SQL = """
            UPDATE blind_date_session_participants
            SET status = 'ELIMINATED', eliminated_at = NOW(), updated_at = NOW()
            WHERE session_id = :sessionId
              AND status IN ('ACTIVE', 'ADVANCED')
              AND id <> :finalistParticipantId
            """;

    private static final String MOVE_ADVANCED_TO_ROUND_SQL = """
            UPDATE blind_date_session_participants
            SET current_round_id = :newRoundId, updated_at = NOW()
            WHERE session_id = :sessionId
              AND status = 'ADVANCED'
              AND current_round_id = :previousRoundId
            """;

    private static final String PROMOTE_TO_FINALIST_SQL = """
            UPDATE blind_date_session_participants
            SET status = 'FINALIST', finalist_at = NOW(), updated_at = NOW()
            WHERE id = :participantId
              AND status IN ('ACTIVE', 'ADVANCED')
            """;

    private static final String MARK_REVEALED_SQL = """
            UPDATE blind_date_session_participants
            SET status = 'REVEALED', revealed_at = NOW(), updated_at = NOW()
            WHERE id = :participantId
              AND status = 'FINALIST'
            """;

    private static final String WITHDRAW_PARTICIPANT_SQL = """
            UPDATE blind_date_session_participants
            SET status = 'WITHDRAWN', withdrawn_at = NOW(), updated_at = NOW()
            WHERE id = :participantId
              AND status IN ('ACTIVE', 'ADVANCED', 'FINALIST', 'REVEALED')
            """;

    private static final String EXISTS_ACTIVE_PARTICIPATION_SQL = """
            SELECT COUNT(1) FROM blind_date_session_participants
            WHERE session_id = :sessionId AND user_id = :userId
            """;

    // ── Answers ────────────────────────────────────────────────────────────

    private static final String UPSERT_ANSWER_SQL = """
            INSERT INTO blind_date_session_answers (participant_id, session_question_id, answer)
            VALUES (:participantId, :sessionQuestionId, :answer)
            ON CONFLICT (participant_id, session_question_id) DO UPDATE
                SET answer = EXCLUDED.answer, updated_at = NOW()
            """;

    private static final String FIND_ANSWERS_FOR_PARTICIPANT_ROUND_SQL = """
            SELECT a.id, a.participant_id, a.session_question_id, a.answer, a.submitted_at
            FROM blind_date_session_answers a
            JOIN blind_date_session_questions q ON q.id = a.session_question_id
            WHERE a.participant_id = :participantId
              AND q.round_id = :roundId
            """;

    private static final String FIND_ANSWERS_WITH_QUESTIONS_SQL = """
            SELECT a.session_question_id, q.question_text, a.answer, a.submitted_at
            FROM blind_date_session_answers a
            JOIN blind_date_session_questions q ON q.id = a.session_question_id
            WHERE a.participant_id = :participantId
              AND q.round_id = :roundId
            ORDER BY q.sort_order
            """;

    private static final String IS_ANSWER_EDITABLE_SQL = """
            SELECT COUNT(1) = 0
            FROM blind_date_round_selections rs
            JOIN blind_date_session_questions q ON q.round_id = rs.round_id
            WHERE rs.participant_id = :participantId
              AND q.id = :sessionQuestionId
            """;

    // ── Round selections ───────────────────────────────────────────────────

    private static final String INSERT_SELECTION_SQL = """
            INSERT INTO blind_date_round_selections
                (round_id, participant_id, selected_by_user_id, decision)
            VALUES (:roundId, :participantId, :selectedByUserId, :decision)
            ON CONFLICT (round_id, participant_id) DO UPDATE
                SET decision = EXCLUDED.decision, selected_by_user_id = EXCLUDED.selected_by_user_id
            RETURNING id
            """;

    private static final String FIND_SELECTION_SQL = """
            SELECT id, round_id, participant_id, selected_by_user_id, decision, created_at
            FROM blind_date_round_selections
            WHERE round_id = :roundId AND participant_id = :participantId
            """;

    private static final String FIND_SELECTIONS_FOR_ROUND_SQL = """
            SELECT id, round_id, participant_id, selected_by_user_id, decision, created_at
            FROM blind_date_round_selections
            WHERE round_id = :roundId
            """;

    private static final String EXISTS_FINALIST_IN_SESSION_SQL = """
            SELECT COUNT(1) FROM blind_date_session_participants
            WHERE session_id = :sessionId AND status IN ('FINALIST', 'REVEALED')
            """;

    private static final String COUNT_STILL_ACTIVE_SQL = """
            SELECT COUNT(1) FROM blind_date_session_participants
            WHERE session_id = :sessionId
              AND status IN ('ACTIVE', 'ADVANCED', 'FINALIST', 'REVEALED')
            """;

    private static final String BLOCK_EXISTS_SQL = """
            SELECT COUNT(1) FROM user_blocks
            WHERE status = 'ACTIVE'
              AND ((blocker_user_id = :userA AND blocked_user_id = :userB)
                OR (blocker_user_id = :userB AND blocked_user_id = :userA))
            """;

    private static final String ELIMINATE_STILL_ACTIVE_IN_NON_OPEN_SESSIONS_SQL = """
            UPDATE blind_date_session_participants p
            SET status = 'ELIMINATED', eliminated_at = NOW(), updated_at = NOW()
            FROM blind_date_sessions s
            WHERE s.id = p.session_id
              AND s.status <> 'OPEN'
              AND p.status IN ('ACTIVE', 'ADVANCED')
            RETURNING p.id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public BlindDateParticipantRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Participants ───────────────────────────────────────────────────────

    public UUID insertParticipant(UUID sessionId, UUID userId, UUID roundId, UUID idempotencyKey) {
        var params = new MapSqlParameterSource()
                .addValue("sessionId", sessionId)
                .addValue("userId", userId)
                .addValue("roundId", roundId)
                .addValue("idempotencyKey", idempotencyKey);
        return jdbc.query(INSERT_PARTICIPANT_SQL, params, (rs, i) -> rs.getObject("id", UUID.class))
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Failed to create Blind Date participant"));
    }

    public Optional<ParticipantRow> findParticipant(UUID participantId) {
        return jdbc.query(FIND_PARTICIPANT_BY_ID_SQL, new MapSqlParameterSource("participantId", participantId), this::mapParticipant)
                .stream().findFirst();
    }

    public Optional<ParticipantRow> findParticipantForUpdate(UUID participantId) {
        return jdbc.query(FIND_PARTICIPANT_BY_ID_FOR_UPDATE_SQL, new MapSqlParameterSource("participantId", participantId), this::mapParticipant)
                .stream().findFirst();
    }

    public Optional<ParticipantRow> findBySessionAndUser(UUID sessionId, UUID userId) {
        var params = new MapSqlParameterSource().addValue("sessionId", sessionId).addValue("userId", userId);
        return jdbc.query(FIND_PARTICIPANT_BY_SESSION_AND_USER_SQL, params, this::mapParticipant)
                .stream().findFirst();
    }

    public Optional<ParticipantRow> findByIdempotencyKey(UUID idempotencyKey) {
        return jdbc.query(FIND_BY_IDEMPOTENCY_KEY_SQL, new MapSqlParameterSource("idempotencyKey", idempotencyKey), this::mapParticipant)
                .stream().findFirst();
    }

    public List<ParticipantRow> findParticipantsForSession(UUID sessionId) {
        return jdbc.query(FIND_PARTICIPANTS_FOR_SESSION_SQL, new MapSqlParameterSource("sessionId", sessionId), this::mapParticipant);
    }

    public List<ParticipantRow> findActiveParticipantsInRound(UUID roundId) {
        return jdbc.query(FIND_ACTIVE_PARTICIPANTS_IN_ROUND_SQL, new MapSqlParameterSource("roundId", roundId), this::mapParticipant);
    }

    public List<ParticipantRow> findStillActiveInSession(UUID sessionId) {
        return jdbc.query(FIND_STILL_ACTIVE_IN_SESSION_SQL, new MapSqlParameterSource("sessionId", sessionId), this::mapParticipant);
    }

    public boolean advanceParticipant(UUID participantId, UUID nextRoundId) {
        var params = new MapSqlParameterSource().addValue("participantId", participantId).addValue("nextRoundId", nextRoundId);
        return jdbc.update(ADVANCE_PARTICIPANT_SQL, params) > 0;
    }

    public boolean eliminateParticipant(UUID participantId) {
        return jdbc.update(ELIMINATE_PARTICIPANT_SQL, new MapSqlParameterSource("participantId", participantId)) > 0;
    }

    public int eliminateAllStillActiveExcept(UUID sessionId, UUID finalistParticipantId) {
        var params = new MapSqlParameterSource()
                .addValue("sessionId", sessionId)
                .addValue("finalistParticipantId", finalistParticipantId);
        return jdbc.update(ELIMINATE_ALL_STILL_ACTIVE_EXCEPT_SQL, params);
    }

    public int moveAdvancedToRound(UUID sessionId, UUID previousRoundId, UUID newRoundId) {
        var params = new MapSqlParameterSource()
                .addValue("sessionId", sessionId)
                .addValue("previousRoundId", previousRoundId)
                .addValue("newRoundId", newRoundId);
        return jdbc.update(MOVE_ADVANCED_TO_ROUND_SQL, params);
    }

    public boolean promoteToFinalist(UUID participantId) {
        return jdbc.update(PROMOTE_TO_FINALIST_SQL, new MapSqlParameterSource("participantId", participantId)) > 0;
    }

    public boolean markRevealed(UUID participantId) {
        return jdbc.update(MARK_REVEALED_SQL, new MapSqlParameterSource("participantId", participantId)) > 0;
    }

    public boolean withdrawParticipant(UUID participantId) {
        return jdbc.update(WITHDRAW_PARTICIPANT_SQL, new MapSqlParameterSource("participantId", participantId)) > 0;
    }

    /** True when an ACTIVE block exists between the two users (either direction). */
    public boolean isBlocked(UUID userA, UUID userB) {
        var params = new MapSqlParameterSource().addValue("userA", userA).addValue("userB", userB);
        Integer count = jdbc.queryForObject(BLOCK_EXISTS_SQL, params, Integer.class);
        return count != null && count > 0;
    }

    /**
     * Self-healing sweep: eliminates ACTIVE/ADVANCED participants whose session
     * is no longer OPEN. Covers the narrow race where a join commits just after
     * the expiry worker's per-session cleanup.
     *
     * @return ids of the eliminated participants
     */
    public List<UUID> eliminateStillActiveInNonOpenSessions() {
        return jdbc.query(ELIMINATE_STILL_ACTIVE_IN_NON_OPEN_SESSIONS_SQL,
                new MapSqlParameterSource(), (rs, i) -> rs.getObject("id", UUID.class));
    }

    public boolean hasParticipated(UUID sessionId, UUID userId) {
        var params = new MapSqlParameterSource().addValue("sessionId", sessionId).addValue("userId", userId);
        Integer count = jdbc.queryForObject(EXISTS_ACTIVE_PARTICIPATION_SQL, params, Integer.class);
        return count != null && count > 0;
    }

    // ── Answers ────────────────────────────────────────────────────────────

    public void upsertAnswer(UUID participantId, UUID sessionQuestionId, String answer) {
        var params = new MapSqlParameterSource()
                .addValue("participantId", participantId)
                .addValue("sessionQuestionId", sessionQuestionId)
                .addValue("answer", answer);
        jdbc.update(UPSERT_ANSWER_SQL, params);
    }

    public List<AnswerRow> findAnswersForParticipantRound(UUID participantId, UUID roundId) {
        var params = new MapSqlParameterSource().addValue("participantId", participantId).addValue("roundId", roundId);
        return jdbc.query(FIND_ANSWERS_FOR_PARTICIPANT_ROUND_SQL, params, (rs, i) -> new AnswerRow(
                rs.getObject("id", UUID.class),
                rs.getObject("participant_id", UUID.class),
                rs.getObject("session_question_id", UUID.class),
                rs.getString("answer"),
                rs.getObject("submitted_at", OffsetDateTime.class)));
    }

    public List<ParticipantAnswerView> findAnswersWithQuestions(UUID participantId, UUID roundId) {
        var params = new MapSqlParameterSource()
                .addValue("participantId", participantId)
                .addValue("roundId", roundId);
        return jdbc.query(FIND_ANSWERS_WITH_QUESTIONS_SQL, params, (rs, i) -> new ParticipantAnswerView(
                rs.getObject("session_question_id", UUID.class),
                rs.getString("question_text"),
                rs.getString("answer"),
                rs.getObject("submitted_at", OffsetDateTime.class)));
    }

    public boolean isAnswerEditable(UUID participantId, UUID sessionQuestionId) {
        var params = new MapSqlParameterSource()
                .addValue("participantId", participantId)
                .addValue("sessionQuestionId", sessionQuestionId);
        Boolean editable = jdbc.queryForObject(IS_ANSWER_EDITABLE_SQL, params, Boolean.class);
        return Boolean.TRUE.equals(editable);
    }

    // ── Round selections ───────────────────────────────────────────────────

    public UUID upsertSelection(UUID roundId, UUID participantId, UUID selectedByUserId, String decision) {
        var params = new MapSqlParameterSource()
                .addValue("roundId", roundId)
                .addValue("participantId", participantId)
                .addValue("selectedByUserId", selectedByUserId)
                .addValue("decision", decision);
        return jdbc.query(INSERT_SELECTION_SQL, params, (rs, i) -> rs.getObject("id", UUID.class))
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Failed to record Blind Date selection"));
    }

    public Optional<SelectionRow> findSelection(UUID roundId, UUID participantId) {
        var params = new MapSqlParameterSource().addValue("roundId", roundId).addValue("participantId", participantId);
        return jdbc.query(FIND_SELECTION_SQL, params, this::mapSelection).stream().findFirst();
    }

    public List<SelectionRow> findSelectionsForRound(UUID roundId) {
        return jdbc.query(FIND_SELECTIONS_FOR_ROUND_SQL, new MapSqlParameterSource("roundId", roundId), this::mapSelection);
    }

    public boolean existsFinalistInSession(UUID sessionId) {
        Integer count = jdbc.queryForObject(EXISTS_FINALIST_IN_SESSION_SQL,
                new MapSqlParameterSource("sessionId", sessionId), Integer.class);
        return count != null && count > 0;
    }

    /**
     * Counts participants still occupying a slot: ACTIVE, ADVANCED, FINALIST or
     * REVEALED. Withdrawn and eliminated participants do not count.
     */
    public int countStillActiveInSession(UUID sessionId) {
        Integer count = jdbc.queryForObject(COUNT_STILL_ACTIVE_SQL,
                new MapSqlParameterSource("sessionId", sessionId), Integer.class);
        return count != null ? count : 0;
    }

    // ── Mappers ────────────────────────────────────────────────────────────

    private ParticipantRow mapParticipant(ResultSet rs, int rowNum) throws SQLException {
        return new ParticipantRow(
                rs.getObject("id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("status"),
                rs.getObject("current_round_id", UUID.class),
                rs.getObject("joined_at", OffsetDateTime.class),
                rs.getObject("eliminated_at", OffsetDateTime.class),
                rs.getObject("advanced_at", OffsetDateTime.class),
                rs.getObject("finalist_at", OffsetDateTime.class),
                rs.getObject("withdrawn_at", OffsetDateTime.class),
                rs.getObject("revealed_at", OffsetDateTime.class));
    }

    private SelectionRow mapSelection(ResultSet rs, int rowNum) throws SQLException {
        return new SelectionRow(
                rs.getObject("id", UUID.class),
                rs.getObject("round_id", UUID.class),
                rs.getObject("participant_id", UUID.class),
                rs.getObject("selected_by_user_id", UUID.class),
                rs.getString("decision"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
