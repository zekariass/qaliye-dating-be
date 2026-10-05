package com.qaliye.backend.matchmaking.service;

import com.qaliye.backend.matchmaking.MatchmakingConstants;
import com.qaliye.backend.matchmaking.repository.MatchmakingIntroductionRepository;
import com.qaliye.backend.matchmaking.repository.MatchmakingIntroductionRepository.IntroductionRow;
import com.qaliye.backend.matchmaking.repository.MatchmakingRequestRepository;
import com.qaliye.backend.matchmaking.repository.MatchmakingRequestRepository.RequestRow;
import com.qaliye.backend.notifications.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MatchmakingMatchServiceTest {

    @Mock NamedParameterJdbcTemplate jdbc;
    @Mock MatchmakingIntroductionRepository introductionRepo;
    @Mock MatchmakingRequestRepository requestRepo;
    @Mock NotificationDispatcher notificationDispatcher;

    MatchmakingMatchService service;

    UUID requesterUserId = UUID.randomUUID();
    UUID candidateUserId = UUID.randomUUID();
    UUID requesterRequestId = UUID.randomUUID();
    UUID candidateRequestId = UUID.randomUUID();
    UUID introductionId = UUID.randomUUID();
    UUID matchId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new MatchmakingMatchService(jdbc, introductionRepo, requestRepo, notificationDispatcher);
    }

    @Test
    void createMatch_introductionNotProposed_returnsFalse() {
        var intro = introRow(MatchmakingConstants.INTRO_DECLINED);
        when(introductionRepo.findByIdForUpdate(introductionId)).thenReturn(Optional.of(intro));

        var result = service.createMatchmakingMatch(introductionId);

        assertThat(result.matched()).isFalse();
        verify(jdbc, never()).query(contains("INSERT INTO matches"), any(SqlParameterSource.class),
                any(RowMapper.class));
    }

    @Test
    void createMatch_blockedPair_returnsFalse() {
        var intro = introRow(MatchmakingConstants.INTRO_PROPOSED);
        when(introductionRepo.findByIdForUpdate(introductionId)).thenReturn(Optional.of(intro));
        when(requestRepo.findById(requesterRequestId))
                .thenReturn(Optional.of(requestRow(requesterRequestId, requesterUserId)));
        when(requestRepo.findById(candidateRequestId))
                .thenReturn(Optional.of(requestRow(candidateRequestId, candidateUserId)));
        when(jdbc.queryForObject(contains("user_blocks"), any(SqlParameterSource.class), eq(Integer.class)))
                .thenReturn(1);

        var result = service.createMatchmakingMatch(introductionId);

        assertThat(result.matched()).isFalse();
        assertThat(result.matchId()).isNull();
    }

    @Test
    void createMatch_activeMatchAlreadyExists_returnsFalse() {
        UUID existingMatchId = UUID.randomUUID();
        var intro = introRow(MatchmakingConstants.INTRO_PROPOSED);
        when(introductionRepo.findByIdForUpdate(introductionId)).thenReturn(Optional.of(intro));
        when(requestRepo.findById(requesterRequestId))
                .thenReturn(Optional.of(requestRow(requesterRequestId, requesterUserId)));
        when(requestRepo.findById(candidateRequestId))
                .thenReturn(Optional.of(requestRow(candidateRequestId, candidateUserId)));
        when(jdbc.queryForObject(contains("user_blocks"), any(SqlParameterSource.class), eq(Integer.class)))
                .thenReturn(0);
        when(jdbc.query(contains("FROM matches"), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(existingMatchId));

        var result = service.createMatchmakingMatch(introductionId);

        assertThat(result.matched()).isFalse();
        assertThat(result.matchId()).isEqualTo(existingMatchId);
    }

    @Test
    void createMatch_success_insertsMatchAndNotifies() {
        var intro = introRow(MatchmakingConstants.INTRO_PROPOSED);
        when(introductionRepo.findByIdForUpdate(introductionId)).thenReturn(Optional.of(intro));
        when(requestRepo.findById(requesterRequestId))
                .thenReturn(Optional.of(requestRow(requesterRequestId, requesterUserId)));
        when(requestRepo.findById(candidateRequestId))
                .thenReturn(Optional.of(requestRow(candidateRequestId, candidateUserId)));
        when(jdbc.queryForObject(contains("user_blocks"), any(SqlParameterSource.class), eq(Integer.class)))
                .thenReturn(0);
        when(jdbc.query(contains("FROM matches"), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        when(jdbc.query(contains("INSERT INTO matches"), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(matchId));

        var result = service.createMatchmakingMatch(introductionId);

        assertThat(result.matched()).isTrue();
        assertThat(result.matchId()).isEqualTo(matchId);
        verify(introductionRepo).setMatched(introductionId, matchId);
        verify(requestRepo).setMatched(requesterRequestId, candidateRequestId);
        verify(notificationDispatcher).dispatchMatchNotification(any(), any(), eq(matchId));
        verify(notificationDispatcher).dispatchMatchmakingMatchedNotification(
                any(), any(), eq(introductionId), eq(matchId));
    }

    @Test
    void createMatch_userOrderingIsLexicographic() {
        // Force a specific ordering: the user whose UUID string comes first is userOne
        UUID userLow = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID userHigh = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

        var intro = introRow(MatchmakingConstants.INTRO_PROPOSED);
        when(introductionRepo.findByIdForUpdate(introductionId)).thenReturn(Optional.of(intro));
        when(requestRepo.findById(requesterRequestId))
                .thenReturn(Optional.of(requestRow(requesterRequestId, userHigh)));
        when(requestRepo.findById(candidateRequestId))
                .thenReturn(Optional.of(requestRow(candidateRequestId, userLow)));
        when(jdbc.queryForObject(contains("user_blocks"), any(SqlParameterSource.class), eq(Integer.class)))
                .thenReturn(0);
        when(jdbc.query(contains("FROM matches"), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        when(jdbc.query(contains("INSERT INTO matches"), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(matchId));

        var result = service.createMatchmakingMatch(introductionId);

        assertThat(result.matched()).isTrue();
        // INSERT is called with userLow as user_one_id
        verify(jdbc).query(
                argThat((String sql) -> sql.contains("INSERT INTO matches")),
                argThat((SqlParameterSource src) -> userLow.equals(src.getValue("userOneId"))),
                any(RowMapper.class));
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private IntroductionRow introRow(String status) {
        return new IntroductionRow(introductionId, requesterRequestId, candidateRequestId,
                UUID.randomUUID(),
                MatchmakingConstants.DECISION_INTERESTED,
                MatchmakingConstants.DECISION_INTERESTED,
                status,
                80, 70, 75, "[]", "compatible",
                OffsetDateTime.now(), OffsetDateTime.now(),
                OffsetDateTime.now().plusHours(72),
                null,
                OffsetDateTime.now(), null, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private RequestRow requestRow(UUID id, UUID uid) {
        return new RequestRow(id, uid, UUID.randomUUID(),
                MatchmakingConstants.REQUEST_ON_HOLD, UUID.randomUUID(), null,
                OffsetDateTime.now(), null, null,
                OffsetDateTime.now().plusDays(90), OffsetDateTime.now(), null);
    }
}
