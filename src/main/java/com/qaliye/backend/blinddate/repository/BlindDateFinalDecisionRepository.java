package com.qaliye.backend.blinddate.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class BlindDateFinalDecisionRepository {

    public record FinalDecisionRow(UUID id, UUID sessionId, UUID finalistParticipantId,
                                   OffsetDateTime revealedAt, OffsetDateTime decisionDeadlineAt,
                                   String creatorDecision, String participantDecision,
                                   OffsetDateTime creatorDecidedAt, OffsetDateTime participantDecidedAt,
                                   String outcome, UUID matchId) {}

    /** A final decision row plus the finalist's user id (for per-caller views). */
    public record FinalDecisionWithFinalist(FinalDecisionRow decision, UUID finalistUserId) {}

    private static final String INSERT_SQL = """
            INSERT INTO blind_date_final_decisions
                (session_id, finalist_participant_id, revealed_at, decision_deadline_at)
            VALUES (:sessionId, :finalistParticipantId, NOW(), :decisionDeadlineAt)
            RETURNING id
            """;

    private static final String FIND_BY_SESSION_SQL = """
            SELECT id, session_id, finalist_participant_id, revealed_at, decision_deadline_at,
                   creator_decision, participant_decision, creator_decided_at, participant_decided_at,
                   outcome, match_id
            FROM blind_date_final_decisions
            WHERE session_id = :sessionId
            """;

    private static final String FIND_BY_SESSION_FOR_UPDATE_SQL = """
            SELECT id, session_id, finalist_participant_id, revealed_at, decision_deadline_at,
                   creator_decision, participant_decision, creator_decided_at, participant_decided_at,
                   outcome, match_id
            FROM blind_date_final_decisions
            WHERE session_id = :sessionId
            FOR UPDATE
            """;

    private static final String FIND_BY_SESSION_IDS_SQL = """
            SELECT fd.id, fd.session_id, fd.finalist_participant_id, fd.revealed_at, fd.decision_deadline_at,
                   fd.creator_decision, fd.participant_decision, fd.creator_decided_at, fd.participant_decided_at,
                   fd.outcome, fd.match_id, p.user_id AS finalist_user_id
            FROM blind_date_final_decisions fd
            JOIN blind_date_session_participants p ON p.id = fd.finalist_participant_id
            WHERE fd.session_id = ANY(:sessionIds::UUID[])
            """;

    private static final String FIND_EXPIRED_PENDING_SQL = """
            SELECT id, session_id, finalist_participant_id, revealed_at, decision_deadline_at,
                   creator_decision, participant_decision, creator_decided_at, participant_decided_at,
                   outcome, match_id
            FROM blind_date_final_decisions
            WHERE outcome IS NULL
              AND decision_deadline_at IS NOT NULL
              AND decision_deadline_at <= NOW()
            """;

    private static final String SET_CREATOR_DECISION_SQL = """
            UPDATE blind_date_final_decisions
            SET creator_decision = :decision, creator_decided_at = NOW(), updated_at = NOW()
            WHERE session_id = :sessionId
            """;

    private static final String SET_PARTICIPANT_DECISION_SQL = """
            UPDATE blind_date_final_decisions
            SET participant_decision = :decision, participant_decided_at = NOW(), updated_at = NOW()
            WHERE session_id = :sessionId
            """;

    private static final String SET_OUTCOME_SQL = """
            UPDATE blind_date_final_decisions
            SET outcome = :outcome, match_id = :matchId, updated_at = NOW()
            WHERE session_id = :sessionId
            """;

    private static final String RESOLVE_PENDING_AS_NOT_INTERESTED_SQL = """
            UPDATE blind_date_final_decisions
            SET creator_decision = CASE WHEN creator_decision = 'PENDING' THEN 'NOT_INTERESTED' ELSE creator_decision END,
                creator_decided_at = CASE WHEN creator_decision = 'PENDING' THEN NOW() ELSE creator_decided_at END,
                participant_decision = CASE WHEN participant_decision = 'PENDING' THEN 'NOT_INTERESTED' ELSE participant_decision END,
                participant_decided_at = CASE WHEN participant_decision = 'PENDING' THEN NOW() ELSE participant_decided_at END,
                updated_at = NOW()
            WHERE session_id = :sessionId
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public BlindDateFinalDecisionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(UUID sessionId, UUID finalistParticipantId, OffsetDateTime decisionDeadlineAt) {
        var params = new MapSqlParameterSource()
                .addValue("sessionId", sessionId)
                .addValue("finalistParticipantId", finalistParticipantId)
                .addValue("decisionDeadlineAt", decisionDeadlineAt);
        return jdbc.query(INSERT_SQL, params, (rs, i) -> rs.getObject("id", UUID.class))
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Failed to create Blind Date final decision"));
    }

    public Optional<FinalDecisionRow> findBySession(UUID sessionId) {
        return jdbc.query(FIND_BY_SESSION_SQL, new MapSqlParameterSource("sessionId", sessionId), this::map)
                .stream().findFirst();
    }

    public Optional<FinalDecisionRow> findBySessionForUpdate(UUID sessionId) {
        return jdbc.query(FIND_BY_SESSION_FOR_UPDATE_SQL, new MapSqlParameterSource("sessionId", sessionId), this::map)
                .stream().findFirst();
    }

    /** Batch-loads final decisions (with finalist user id) for the given sessions. */
    public List<FinalDecisionWithFinalist> findBySessionIds(Collection<UUID> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return List.of();
        }
        String idsParam = sessionIds.stream().map(UUID::toString)
                .collect(Collectors.joining(",", "{", "}"));
        return jdbc.query(FIND_BY_SESSION_IDS_SQL,
                new MapSqlParameterSource("sessionIds", idsParam),
                (rs, i) -> new FinalDecisionWithFinalist(map(rs, i),
                        rs.getObject("finalist_user_id", UUID.class)));
    }

    public List<FinalDecisionRow> findExpiredPending() {
        return jdbc.query(FIND_EXPIRED_PENDING_SQL, new MapSqlParameterSource(), this::map);
    }

    public void setCreatorDecision(UUID sessionId, String decision) {
        var params = new MapSqlParameterSource().addValue("sessionId", sessionId).addValue("decision", decision);
        jdbc.update(SET_CREATOR_DECISION_SQL, params);
    }

    public void setParticipantDecision(UUID sessionId, String decision) {
        var params = new MapSqlParameterSource().addValue("sessionId", sessionId).addValue("decision", decision);
        jdbc.update(SET_PARTICIPANT_DECISION_SQL, params);
    }

    public void setOutcome(UUID sessionId, String outcome, UUID matchId) {
        var params = new MapSqlParameterSource()
                .addValue("sessionId", sessionId)
                .addValue("outcome", outcome)
                .addValue("matchId", matchId);
        jdbc.update(SET_OUTCOME_SQL, params);
    }

    public void resolvePendingAsNotInterested(UUID sessionId) {
        jdbc.update(RESOLVE_PENDING_AS_NOT_INTERESTED_SQL, new MapSqlParameterSource("sessionId", sessionId));
    }

    private FinalDecisionRow map(ResultSet rs, int rowNum) throws SQLException {
        return new FinalDecisionRow(
                rs.getObject("id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getObject("finalist_participant_id", UUID.class),
                rs.getObject("revealed_at", OffsetDateTime.class),
                rs.getObject("decision_deadline_at", OffsetDateTime.class),
                rs.getString("creator_decision"),
                rs.getString("participant_decision"),
                rs.getObject("creator_decided_at", OffsetDateTime.class),
                rs.getObject("participant_decided_at", OffsetDateTime.class),
                rs.getString("outcome"),
                rs.getObject("match_id", UUID.class));
    }
}
