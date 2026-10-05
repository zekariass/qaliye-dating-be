package com.qaliye.backend.matchmaking.service;

import com.qaliye.backend.matchmaking.MatchmakingConstants;
import com.qaliye.backend.matchmaking.repository.MatchmakingIntroductionRepository;
import com.qaliye.backend.matchmaking.repository.MatchmakingRequestRepository;
import com.qaliye.backend.notifications.NotificationDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

/**
 * Creates the match record for a matchmaking introduction where both users
 * recorded an INTERESTED decision.
 *
 * <p>Mirrors the approach used by {@link com.qaliye.backend.blinddate.service.BlindDateMatchService}:
 * user IDs are ordered lexicographically to match the DB constraint, blocks are
 * checked, and concurrent insert races surface as a retryable 409.
 */
@Service
public class MatchmakingMatchService {

    private static final Logger log = LoggerFactory.getLogger(MatchmakingMatchService.class);

    public record MatchResult(boolean matched, UUID matchId) {}

    private static final String BLOCK_EXISTS_SQL = """
            SELECT COUNT(1) FROM user_blocks
            WHERE status = 'ACTIVE'
              AND ((blocker_user_id = :userA AND blocked_user_id = :userB)
                OR (blocker_user_id = :userB AND blocked_user_id = :userA))
            """;

    private static final String FIND_ACTIVE_MATCH_SQL = """
            SELECT id FROM matches
            WHERE user_one_id = :userOneId
              AND user_two_id = :userTwoId
              AND status = 'ACTIVE'
            """;

    private static final String INSERT_MATCH_SQL = """
            INSERT INTO matches
                (user_one_id, user_two_id, match_source, matchmaking_introduction_id)
            VALUES
                (:userOneId, :userTwoId, 'MATCHMAKING', :introductionId)
            RETURNING id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final MatchmakingIntroductionRepository introductionRepo;
    private final MatchmakingRequestRepository requestRepo;
    private final NotificationDispatcher notificationDispatcher;

    public MatchmakingMatchService(NamedParameterJdbcTemplate jdbc,
                                   MatchmakingIntroductionRepository introductionRepo,
                                   MatchmakingRequestRepository requestRepo,
                                   NotificationDispatcher notificationDispatcher) {
        this.jdbc = jdbc;
        this.introductionRepo = introductionRepo;
        this.requestRepo = requestRepo;
        this.notificationDispatcher = notificationDispatcher;
    }

    /**
     * Creates the match between requester and candidate users when both recorded
     * an INTERESTED decision on the introduction.
     *
     * @param introductionId the introduction with mutual INTERESTED decisions
     * @return match result — matched=false if a block exists or match already present
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public MatchResult createMatchmakingMatch(UUID introductionId) {
        var intro = introductionRepo.findByIdForUpdate(introductionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Introduction not found: " + introductionId));

        if (!MatchmakingConstants.INTRO_PROPOSED.equals(intro.status())) {
            log.warn("createMatchmakingMatch: introduction {} is in status {}, expected PROPOSED",
                    introductionId, intro.status());
            return new MatchResult(false, null);
        }

        var requesterRequest = requestRepo.findById(intro.requesterRequestId()).orElse(null);
        var candidateRequest = requestRepo.findById(intro.candidateRequestId()).orElse(null);
        if (requesterRequest == null || candidateRequest == null) {
            log.error("createMatchmakingMatch: missing request rows for introduction {}", introductionId);
            return new MatchResult(false, null);
        }

        UUID userA = requesterRequest.userId();
        UUID userB = candidateRequest.userId();

        if (isBlocked(userA, userB)) {
            log.info("Matchmaking match skipped for introduction {}: block exists between {} and {}",
                    introductionId, userA, userB);
            return new MatchResult(false, null);
        }

        boolean aIsLower = userA.toString().compareTo(userB.toString()) < 0;
        UUID userOne = aIsLower ? userA : userB;
        UUID userTwo = aIsLower ? userB : userA;

        Optional<UUID> existingMatch = findActiveMatch(userOne, userTwo);
        if (existingMatch.isPresent()) {
            log.info("Matchmaking match: active match {} already exists for introduction {}",
                    existingMatch.get(), introductionId);
            return new MatchResult(false, existingMatch.get());
        }

        UUID matchId;
        try {
            matchId = insertMatch(userOne, userTwo, introductionId);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "match_conflict", e);
        }

        introductionRepo.setMatched(introductionId, matchId);
        requestRepo.setMatched(requesterRequest.id(), candidateRequest.id());

        notificationDispatcher.dispatchMatchNotification(userOne, userTwo, matchId);
        notificationDispatcher.dispatchMatchmakingMatchedNotification(
                userA, userB, introductionId, matchId);

        log.info("Matchmaking match created: id={}, introduction={}", matchId, introductionId);
        return new MatchResult(true, matchId);
    }

    private boolean isBlocked(UUID userA, UUID userB) {
        var params = new MapSqlParameterSource().addValue("userA", userA).addValue("userB", userB);
        Integer count = jdbc.queryForObject(BLOCK_EXISTS_SQL, params, Integer.class);
        return count != null && count > 0;
    }

    private Optional<UUID> findActiveMatch(UUID userOne, UUID userTwo) {
        var params = new MapSqlParameterSource()
                .addValue("userOneId", userOne)
                .addValue("userTwoId", userTwo);
        return jdbc.query(FIND_ACTIVE_MATCH_SQL, params, (rs, i) -> rs.getObject("id", UUID.class))
                .stream().findFirst();
    }

    private UUID insertMatch(UUID userOne, UUID userTwo, UUID introductionId) {
        var params = new MapSqlParameterSource()
                .addValue("userOneId", userOne)
                .addValue("userTwoId", userTwo)
                .addValue("introductionId", introductionId);
        return jdbc.query(INSERT_MATCH_SQL, params, (rs, i) -> rs.getObject("id", UUID.class))
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Failed to insert matchmaking match"));
    }
}
