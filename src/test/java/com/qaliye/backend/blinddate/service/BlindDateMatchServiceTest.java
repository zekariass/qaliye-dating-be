package com.qaliye.backend.blinddate.service;

import com.qaliye.backend.notifications.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BlindDateMatchServiceTest {

    @Mock NamedParameterJdbcTemplate jdbc;
    @Mock NotificationDispatcher notificationDispatcher;
    @Mock org.springframework.transaction.PlatformTransactionManager transactionManager;

    BlindDateMatchService service;
    UUID userA = UUID.randomUUID();
    UUID userB = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new BlindDateMatchService(jdbc, notificationDispatcher, transactionManager);
    }

    @Test
    void createMatch_blockedPair_returnsNoMatch() {
        when(jdbc.queryForObject(contains("user_blocks"), any(SqlParameterSource.class), eq(Integer.class)))
                .thenReturn(1);

        var result = service.createBlindDateMatch(userA, userB, sessionId);

        assertThat(result.matched()).isFalse();
        assertThat(result.matchId()).isNull();
        verify(jdbc, never()).query(contains("INSERT INTO matches"), any(SqlParameterSource.class), any(RowMapper.class));
    }

    @Test
    void createMatch_existingActiveMatch_returnsAlreadyMatched() {
        UUID existingMatchId = UUID.randomUUID();
        when(jdbc.queryForObject(contains("user_blocks"), any(SqlParameterSource.class), eq(Integer.class)))
                .thenReturn(0);
        when(jdbc.query(contains("FROM matches"), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(existingMatchId));

        var result = service.createBlindDateMatch(userA, userB, sessionId);

        assertThat(result.matched()).isFalse();
        assertThat(result.matchId()).isEqualTo(existingMatchId);
    }

    @Test
    void createMatch_success_insertsActionsAndMatch() {
        UUID actionA = UUID.randomUUID();
        UUID actionB = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();

        when(jdbc.queryForObject(contains("user_blocks"), any(SqlParameterSource.class), eq(Integer.class)))
                .thenReturn(0);
        // No existing match
        when(jdbc.query(contains("FROM matches"), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        // No existing blind-date actions for either direction
        when(jdbc.query(contains("FROM user_discovery_actions"), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        // Insert action A then B
        when(jdbc.query(contains("INSERT INTO user_discovery_actions"), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(actionA), List.of(actionB));
        // Insert match
        when(jdbc.query(contains("INSERT INTO matches"), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(matchId));

        var result = service.createBlindDateMatch(userA, userB, sessionId);

        assertThat(result.matched()).isTrue();
        assertThat(result.matchId()).isEqualTo(matchId);
        verify(notificationDispatcher).dispatchMatchNotification(any(), any(), eq(matchId));
    }

    @Test
    void createMatch_existingActiveLike_reusedWithoutInsert() {
        UUID existingActionId = UUID.randomUUID();
        UUID newActionId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();

        when(jdbc.queryForObject(contains("user_blocks"), any(SqlParameterSource.class), eq(Integer.class)))
                .thenReturn(0);
        when(jdbc.query(contains("FROM matches"), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        // First direction already has an ACTIVE like (e.g. organic); second has none
        when(jdbc.query(contains("FROM user_discovery_actions"), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(existingActionId), List.of());
        when(jdbc.query(contains("INSERT INTO user_discovery_actions"), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(newActionId));
        when(jdbc.query(contains("INSERT INTO matches"), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(matchId));

        var result = service.createBlindDateMatch(userA, userB, sessionId);

        assertThat(result.matched()).isTrue();
        // Only one insert (for the direction with no active like)
        verify(jdbc, times(1)).query(contains("INSERT INTO user_discovery_actions"),
                any(SqlParameterSource.class), any(RowMapper.class));
    }

    @Test
    void createMatch_noActiveLike_insertsFreshBlindDateAction() {
        UUID newActionId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();

        when(jdbc.queryForObject(contains("user_blocks"), any(SqlParameterSource.class), eq(Integer.class)))
                .thenReturn(0);
        when(jdbc.query(contains("FROM matches"), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        // No ACTIVE like in either direction (a REVERSED row would not be returned
        // by the status='ACTIVE' lookup, so it is simply absent here)
        when(jdbc.query(contains("FROM user_discovery_actions"), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(), List.of());
        when(jdbc.query(contains("INSERT INTO user_discovery_actions"), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(newActionId), List.of(UUID.randomUUID()));
        when(jdbc.query(contains("INSERT INTO matches"), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(matchId));

        var result = service.createBlindDateMatch(userA, userB, sessionId);

        assertThat(result.matched()).isTrue();
        verify(jdbc, times(2)).query(contains("INSERT INTO user_discovery_actions"),
                any(SqlParameterSource.class), any(RowMapper.class));
    }
}
