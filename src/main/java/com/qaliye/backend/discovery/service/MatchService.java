package com.qaliye.backend.discovery.service;

import com.qaliye.backend.discovery.config.DiscoveryProperties;
import com.qaliye.backend.discovery.dto.MatchSummaryDto;
import com.qaliye.backend.discovery.dto.MatchedUserSummaryDto;
import com.qaliye.backend.discovery.repository.DiscoveryActionRepository;
import com.qaliye.backend.discovery.repository.DiscoveryMatchRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class MatchService {

    private final DiscoveryMatchRepository matchRepo;
    private final DiscoveryActionRepository actionRepo;
    private final StorageSigningService signingService;
    private final DiscoveryProperties props;
    private final NamedParameterJdbcTemplate jdbc;

    public MatchService(DiscoveryMatchRepository matchRepo,
                        DiscoveryActionRepository actionRepo,
                        StorageSigningService signingService,
                        DiscoveryProperties props,
                        NamedParameterJdbcTemplate jdbc) {
        this.matchRepo = matchRepo;
        this.actionRepo = actionRepo;
        this.signingService = signingService;
        this.props = props;
        this.jdbc = jdbc;
    }

    private static final String FETCH_ACTIVE_MATCH_BY_ACTION_SQL = """
            SELECT id, user_one_id, user_two_id, status, matched_at, rewind_eligible_until, first_message_at
            FROM matches
            WHERE created_by_action_id = :actionId
              AND status = 'ACTIVE'
            FOR UPDATE
            """;

    private static final String FETCH_DISPLAY_NAME_SQL = """
            SELECT p.display_name,
                   pp.storage_bucket,
                   pp.storage_path
            FROM profiles p
            LEFT JOIN profile_photos pp
                ON pp.user_id = p.user_id
               AND pp.is_primary = TRUE
               AND pp.moderation_status = 'APPROVED'
               AND pp.deleted_at IS NULL
            WHERE p.user_id = :userId
            """;

    public Optional<MatchSummaryDto> tryCreateMatch(UUID actorId, UUID targetId,
                                                     UUID actorActionId, UUID targetActionId) {
        boolean actorIsOne = actorId.toString().compareTo(targetId.toString()) < 0;
        UUID userOneId = actorIsOne ? actorId : targetId;
        UUID userTwoId = actorIsOne ? targetId : actorId;

        if (matchRepo.findActiveByPair(userOneId, userTwoId).isPresent()) {
            return Optional.empty();
        }

        UUID userOneLikeActionId = actorIsOne ? actorActionId : targetActionId;
        UUID userTwoLikeActionId = actorIsOne ? targetActionId : actorActionId;

        DiscoveryMatchRepository.MatchRow matchRow = matchRepo.insertMatch(
                userOneId, userTwoId,
                userOneLikeActionId, userTwoLikeActionId,
                actorActionId,
                props.getRewind().matchGracePeriodMinutes()
        );

        MatchSummaryDto summary = buildMatchSummary(matchRow, actorId, targetId);
        return Optional.of(summary);
    }

    public Optional<DiscoveryMatchRepository.MatchRow> findActiveMatchByAction(UUID actionId) {
        var params = new MapSqlParameterSource("actionId", actionId);
        DiscoveryMatchRepository.MatchRow row = jdbc.query(FETCH_ACTIVE_MATCH_BY_ACTION_SQL, params, rs -> {
            if (!rs.next()) return null;
            return new DiscoveryMatchRepository.MatchRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("user_one_id", UUID.class),
                    rs.getObject("user_two_id", UUID.class),
                    rs.getString("status"),
                    rs.getObject("matched_at", java.time.OffsetDateTime.class),
                    rs.getObject("rewind_eligible_until", java.time.OffsetDateTime.class),
                    rs.getObject("first_message_at", java.time.OffsetDateTime.class)
            );
        });
        return Optional.ofNullable(row);
    }

    public int endMatch(UUID matchId, String reason, UUID endedByUserId) {
        return matchRepo.endMatch(matchId, reason, endedByUserId);
    }

    private MatchSummaryDto buildMatchSummary(DiscoveryMatchRepository.MatchRow matchRow,
                                               UUID actorId, UUID targetId) {
        MatchedUserSummaryDto otherUser = loadUserSummary(targetId);
        Instant matchedAt = matchRow.matchedAt() != null
                ? matchRow.matchedAt().toInstant() : Instant.now();
        Instant rewindEligibleUntil = matchRow.rewindEligibleUntil() != null
                ? matchRow.rewindEligibleUntil().toInstant() : null;
        return new MatchSummaryDto(matchRow.id(), matchedAt, rewindEligibleUntil, otherUser);
    }

    private MatchedUserSummaryDto loadUserSummary(UUID userId) {
        var params = new MapSqlParameterSource("userId", userId);
        return jdbc.query(FETCH_DISPLAY_NAME_SQL, params, rs -> {
            if (!rs.next()) return new MatchedUserSummaryDto(userId, "Unknown", null);
            String bucket = rs.getString("storage_bucket");
            String path = rs.getString("storage_path");
            String photoUrl = (bucket != null && path != null) ? signingService.sign(bucket, path) : null;
            return new MatchedUserSummaryDto(userId, rs.getString("display_name"), photoUrl);
        });
    }
}
