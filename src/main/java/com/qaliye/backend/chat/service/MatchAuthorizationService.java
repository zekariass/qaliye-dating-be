package com.qaliye.backend.chat.service;

import com.qaliye.backend.chat.exception.*;
import com.qaliye.backend.chat.repository.ChatMatchRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class MatchAuthorizationService {

    private final ChatMatchRepository matchRepository;
    private final NamedParameterJdbcTemplate jdbc;

    private static final String ACCOUNT_STATUS_SQL =
            "SELECT status FROM app_users WHERE id = :userId";

    private static final String ACTIVE_BLOCK_SQL = """
            SELECT 1 FROM user_blocks
            WHERE status = 'ACTIVE'
              AND (
                  (blocker_user_id = :userOneId AND blocked_user_id = :userTwoId)
                  OR (blocker_user_id = :userTwoId AND blocked_user_id = :userOneId)
              )
            """;

    public MatchAuthorizationService(ChatMatchRepository matchRepository,
                                     NamedParameterJdbcTemplate jdbc) {
        this.matchRepository = matchRepository;
        this.jdbc = jdbc;
    }

    public record MatchContext(
            UUID matchId,
            UUID userOneId,
            UUID userTwoId,
            boolean isUserOne,
            long nextMessageSequence,
            long userOneLastDeliveredSequence,
            long userTwoLastDeliveredSequence,
            long userOneLastReadSequence,
            long userTwoLastReadSequence
    ) {
        public UUID otherUserId() { return isUserOne ? userTwoId : userOneId; }
        public UUID callerId()    { return isUserOne ? userOneId : userTwoId; }
        public long myLastDeliveredSequence() {
            return isUserOne ? userOneLastDeliveredSequence : userTwoLastDeliveredSequence;
        }
        public long myLastReadSequence() {
            return isUserOne ? userOneLastReadSequence : userTwoLastReadSequence;
        }
        public long theirLastDeliveredSequence() {
            return isUserOne ? userTwoLastDeliveredSequence : userOneLastDeliveredSequence;
        }
        public long theirLastReadSequence() {
            return isUserOne ? userTwoLastReadSequence : userOneLastReadSequence;
        }
    }

    public void requireActiveAccount(UUID callerId) {
        List<String> statuses = jdbc.query(
                ACCOUNT_STATUS_SQL,
                new MapSqlParameterSource("userId", callerId),
                (rs, n) -> rs.getString("status"));
        if (statuses.isEmpty() || !"ACTIVE".equals(statuses.get(0))) {
            throw new AccountNotActiveException();
        }
    }

    public MatchContext authorize(UUID callerId, UUID matchId) {
        requireActiveAccount(callerId);
        ChatMatchRepository.MatchRow match = matchRepository.findById(matchId)
                .orElseThrow(MatchNotFoundException::new);
        return buildContext(callerId, match);
    }

    public MatchContext authorizeActive(UUID callerId, UUID matchId) {
        requireActiveAccount(callerId);
        ChatMatchRepository.MatchRow match = matchRepository.findById(matchId)
                .orElseThrow(MatchNotFoundException::new);
        if (!match.isParticipant(callerId)) throw new MatchAccessDeniedException();
        if (!"ACTIVE".equals(match.status())) throw new MatchNotActiveException();
        checkNoActiveBlock(match.userOneId(), match.userTwoId());
        return buildContext(callerId, match);
    }

    public MatchContext authorizeActiveForUpdate(UUID callerId, UUID matchId) {
        requireActiveAccount(callerId);
        ChatMatchRepository.MatchRow match = matchRepository.findByIdForUpdate(matchId)
                .orElseThrow(MatchNotFoundException::new);
        if (!match.isParticipant(callerId)) throw new MatchAccessDeniedException();
        if (!"ACTIVE".equals(match.status())) throw new MatchNotActiveException();
        checkNoActiveBlock(match.userOneId(), match.userTwoId());
        return buildContext(callerId, match);
    }

    public void checkNoActiveBlock(UUID userOneId, UUID userTwoId) {
        var params = new MapSqlParameterSource()
                .addValue("userOneId", userOneId)
                .addValue("userTwoId", userTwoId);
        List<Integer> blocked = jdbc.query(ACTIVE_BLOCK_SQL, params, (rs, n) -> 1);
        if (!blocked.isEmpty()) throw new UserBlockedException();
    }

    private MatchContext buildContext(UUID callerId, ChatMatchRepository.MatchRow match) {
        if (!match.isParticipant(callerId)) throw new MatchAccessDeniedException();
        return new MatchContext(
                match.id(),
                match.userOneId(),
                match.userTwoId(),
                match.isUserOne(callerId),
                match.nextMessageSequence(),
                match.userOneLastDeliveredSequence(),
                match.userTwoLastDeliveredSequence(),
                match.userOneLastReadSequence(),
                match.userTwoLastReadSequence()
        );
    }
}
