package com.qaliye.backend.matchmaking.service;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin dashboard stats for the Personal Matchmaking feature.
 *
 * <p>Status breakdowns (requests / introductions) plus a small set of funnel
 * counts the matchmaker checks daily: introductions awaiting a decision, and
 * total matches produced.
 */
@Service
public class MatchmakingAdminDashboardService {

    private static final String REQUEST_STATUS_COUNTS_SQL = """
            SELECT status, COUNT(*) AS n
            FROM matchmaking_requests
            GROUP BY status
            """;

    private static final String INTRO_STATUS_COUNTS_SQL = """
            SELECT status, COUNT(*) AS n
            FROM matchmaking_introductions
            GROUP BY status
            """;

    /** Introductions in PROPOSED where at least one side hasn't decided. */
    private static final String AWAITING_DECISION_SQL = """
            SELECT COUNT(*) FROM matchmaking_introductions
            WHERE status = 'PROPOSED'
              AND (requester_decision = 'PENDING' OR candidate_decision = 'PENDING')
            """;

    private static final String MATCHMAKING_MATCHES_SQL = """
            SELECT COUNT(*) FROM matches WHERE match_source = 'MATCHMAKING'
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public MatchmakingAdminDashboardService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Snapshot of the whole funnel. Status maps contain only non-zero entries
     * (e.g. {@code {"OPEN": 12, "ON_HOLD": 3}}).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getStats() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requests", statusCounts(REQUEST_STATUS_COUNTS_SQL));
        result.put("introductions", statusCounts(INTRO_STATUS_COUNTS_SQL));

        Map<String, Object> actionNeeded = new LinkedHashMap<>();
        actionNeeded.put("awaitingDecision", count(AWAITING_DECISION_SQL));
        result.put("actionNeeded", actionNeeded);

        result.put("totalMatchmakingMatches", count(MATCHMAKING_MATCHES_SQL));
        return result;
    }

    private Map<String, Long> statusCounts(String sql) {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query(sql, new MapSqlParameterSource(),
                (rs, i) -> Map.entry(rs.getString("status"), rs.getLong("n")))
                .forEach(e -> counts.put(e.getKey(), e.getValue()));
        return counts;
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, new MapSqlParameterSource(), Long.class);
        return n != null ? n : 0;
    }
}
