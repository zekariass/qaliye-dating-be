package com.qaliye.backend.discovery.service;

import com.qaliye.backend.billing.repository.ActionLimitRepository;
import com.qaliye.backend.billing.service.ActionCostService;
import com.qaliye.backend.billing.service.CreditService;
import com.qaliye.backend.chat.repository.ChatMatchRepository;
import com.qaliye.backend.chat.repository.ChatMessageRepository;
import com.qaliye.backend.discovery.dto.MatchSummaryDto;
import com.qaliye.backend.discovery.dto.SuperMessageResponse;
import com.qaliye.backend.discovery.repository.DiscoveryActionRepository;
import com.qaliye.backend.notifications.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SuperMessageServiceTest {

    @Mock NamedParameterJdbcTemplate jdbc;
    @Mock ActionCostService actionCostService;
    @Mock CreditService creditService;
    @Mock DiscoveryActionRepository actionRepo;
    @Mock ActionLimitRepository actionLimitRepo;
    @Mock StorageSigningService signingService;
    @Mock SwipeActionService swipeActionService;
    @Mock NotificationDispatcher notificationDispatcher;
    @Mock ChatMatchRepository chatMatchRepository;
    @Mock ChatMessageRepository chatMessageRepository;
    @Mock MatchService matchService;

    SuperMessageService service;

    UUID senderId = UUID.randomUUID();
    UUID receiverId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    String message = "Hi";

    @BeforeEach
    void setUp() {
        service = new SuperMessageService(
                jdbc, actionCostService, creditService, actionRepo, actionLimitRepo,
                signingService, swipeActionService, notificationDispatcher,
                chatMatchRepository, chatMessageRepository, matchService);
    }

    private void mockSendBasics(UUID messageId, UUID actionId) {
        // Idempotency lookup + profile briefs → empty result sets
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class))).thenReturn(List.of());
        when(actionCostService.evaluate(senderId, "SUPER_MESSAGE")).thenReturn(
                new ActionCostService.ActionCostResult(null, 0, true, false, false,
                        LocalDate.now(), LocalDate.now(), 0, null, "DAY"));
        when(jdbc.queryForMap(contains("INSERT INTO pre_match_messages"), any(SqlParameterSource.class)))
                .thenReturn(Map.of("id", messageId, "created_at", OffsetDateTime.now()));
        when(actionRepo.insertAction(senderId, receiverId, "LIKE", idempotencyKey)).thenReturn(
                new DiscoveryActionRepository.ActionRow(
                        actionId, senderId, receiverId, "LIKE", "ACTIVE", idempotencyKey,
                        OffsetDateTime.now(), null));
        when(jdbc.queryForObject(contains("pg_advisory_xact_lock"),
                any(SqlParameterSource.class), eq(Object.class))).thenReturn(new Object());
    }

    @Test
    void send_withMutualLike_marksAcceptedLinksMatchAndCopiesMessageToChat() {
        UUID messageId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        UUID mutualActionId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        Instant respondedAt = Instant.now();
        mockSendBasics(messageId, actionId);

        when(actionRepo.findMutualActiveLike(senderId, receiverId)).thenReturn(
                Optional.of(new DiscoveryActionRepository.ActionRow(
                        mutualActionId, receiverId, senderId, "LIKE", "ACTIVE", UUID.randomUUID(),
                        OffsetDateTime.now(), "HEART")));
        when(matchService.tryCreateMatch(senderId, receiverId, actionId, mutualActionId))
                .thenReturn(Optional.of(new MatchSummaryDto(
                        matchId, "DISCOVERY", Instant.now(), Instant.now().plusSeconds(300), null)));
        when(jdbc.query(contains("status = 'ACCEPTED'"),
                any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(Timestamp.from(respondedAt)));
        when(chatMatchRepository.reserveAndIncrementSequence(matchId)).thenReturn(1L);

        SuperMessageResponse response = service.send(senderId, receiverId, message, idempotencyKey);

        assertThat(response.status()).isEqualTo("ACCEPTED");
        assertThat(response.matchId()).isEqualTo(matchId);
        assertThat(response.respondedAt()).isEqualTo(respondedAt);
        assertThat(response.discoveryActionId()).isEqualTo(actionId);

        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).query(contains("status = 'ACCEPTED'"), params.capture(), any(RowMapper.class));
        MapSqlParameterSource acceptParams = (MapSqlParameterSource) params.getValue();
        assertThat(acceptParams.getValue("id")).isEqualTo(messageId);
        assertThat(acceptParams.getValue("matchId")).isEqualTo(matchId);

        verify(chatMessageRepository).insert(
                eq(matchId), eq(senderId), any(UUID.class), eq("TEXT"), eq(message), eq(1L));
        verify(notificationDispatcher).dispatchMatchNotification(senderId, receiverId, matchId);
        verify(notificationDispatcher).dispatchSuperMessageNotification(senderId, receiverId, messageId);
    }

    @Test
    void send_withoutMutualLike_staysSentAndCreatesNoMatch() {
        UUID messageId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        mockSendBasics(messageId, actionId);

        when(actionRepo.findMutualActiveLike(senderId, receiverId)).thenReturn(Optional.empty());

        SuperMessageResponse response = service.send(senderId, receiverId, message, idempotencyKey);

        assertThat(response.status()).isEqualTo("SENT");
        assertThat(response.matchId()).isNull();
        assertThat(response.respondedAt()).isNull();
        assertThat(response.discoveryActionId()).isEqualTo(actionId);

        verify(matchService, never()).tryCreateMatch(any(), any(), any(), any());
        verify(chatMessageRepository, never())
                .insert(any(), any(), any(), any(), any(), anyLong());
        verify(jdbc, never()).query(contains("status = 'ACCEPTED'"),
                any(SqlParameterSource.class), any(RowMapper.class));
        verify(notificationDispatcher, never()).dispatchMatchNotification(any(), any(), any());
    }
}
