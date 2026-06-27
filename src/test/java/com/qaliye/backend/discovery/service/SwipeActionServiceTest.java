package com.qaliye.backend.discovery.service;

import com.qaliye.backend.discovery.dto.MatchSummaryDto;
import com.qaliye.backend.discovery.dto.UserPlanEntitlement;
import com.qaliye.backend.discovery.exception.DailyLimitExceededException;
import com.qaliye.backend.discovery.exception.DuplicateActiveActionException;
import com.qaliye.backend.discovery.exception.TargetIneligibleException;
import com.qaliye.backend.discovery.repository.DailyLimitRepository;
import com.qaliye.backend.discovery.repository.DiscoveryActionRepository;
import com.qaliye.backend.notifications.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SwipeActionServiceTest {

    @Mock DiscoveryActionRepository actionRepo;
    @Mock DailyLimitRepository dailyLimitRepo;
    @Mock MatchService matchService;
    @Mock PlanEntitlementService entitlementService;
    @Mock NotificationDispatcher notificationDispatcher;
    @Mock NamedParameterJdbcTemplate jdbc;

    SwipeActionService service;

    UUID actorId = UUID.randomUUID();
    UUID targetId = UUID.randomUUID();
    UUID clientActionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SwipeActionService(
                actionRepo, dailyLimitRepo, null, matchService,
                entitlementService, notificationDispatcher, jdbc);
    }

    @Test
    void recordLike_withMutualLike_dispatchesMatchNotification() {
        when(actionRepo.findByClientActionId(actorId, clientActionId)).thenReturn(Optional.empty());
        when(actionRepo.findActiveByPair(actorId, targetId)).thenReturn(Optional.empty());
        when(entitlementService.loadEntitlement(actorId)).thenReturn(
                new UserPlanEntitlement(actorId, "FREE", false, 10, 3, 1, 0, 0));
        when(dailyLimitRepo.lockForUpdate(actorId)).thenReturn(
                Optional.of(new DailyLimitRepository.DailyLimitRow(actorId, 0, 0, 0)));
        when(actionRepo.insertAction(actorId, targetId, "LIKE", clientActionId)).thenReturn(
                new DiscoveryActionRepository.ActionRow(
                        UUID.randomUUID(), actorId, targetId, "LIKE", "ACTIVE", clientActionId,
                        OffsetDateTime.now()));
        when(actionRepo.findMutualActiveLike(actorId, targetId)).thenReturn(
                Optional.of(new DiscoveryActionRepository.ActionRow(
                        UUID.randomUUID(), targetId, actorId, "LIKE", "ACTIVE", UUID.randomUUID(),
                        OffsetDateTime.now())));
        UUID matchId = UUID.randomUUID();
        when(matchService.tryCreateMatch(actorId, targetId, any(UUID.class), any(UUID.class)))
                .thenReturn(Optional.of(new MatchSummaryDto(matchId, Instant.now(), Instant.now().plusSeconds(300))));

        service.recordLike(actorId, targetId, clientActionId);

        ArgumentCaptor<UUID> userOneCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<UUID> userTwoCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<UUID> matchIdCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(notificationDispatcher).dispatchMatchNotification(
                userOneCaptor.capture(), userTwoCaptor.capture(), matchIdCaptor.capture());

        assertThat(userOneCaptor.getValue()).isEqualTo(actorId);
        assertThat(userTwoCaptor.getValue()).isEqualTo(targetId);
        assertThat(matchIdCaptor.getValue()).isEqualTo(matchId);
    }

    @Test
    void recordLike_withoutMutualLike_doesNotDispatchNotification() {
        when(actionRepo.findByClientActionId(actorId, clientActionId)).thenReturn(Optional.empty());
        when(actionRepo.findActiveByPair(actorId, targetId)).thenReturn(Optional.empty());
        when(entitlementService.loadEntitlement(actorId)).thenReturn(
                new UserPlanEntitlement(actorId, "FREE", false, 10, 3, 1, 0, 0));
        when(dailyLimitRepo.lockForUpdate(actorId)).thenReturn(
                Optional.of(new DailyLimitRepository.DailyLimitRow(actorId, 0, 0, 0)));
        when(actionRepo.insertAction(actorId, targetId, "LIKE", clientActionId)).thenReturn(
                new DiscoveryActionRepository.ActionRow(
                        UUID.randomUUID(), actorId, targetId, "LIKE", "ACTIVE", clientActionId,
                        OffsetDateTime.now()));
        when(actionRepo.findMutualActiveLike(actorId, targetId)).thenReturn(Optional.empty());

        service.recordLike(actorId, targetId, clientActionId);

        verify(notificationDispatcher, never()).dispatchMatchNotification(any(), any(), any());
    }

    @Test
    void recordSuperLike_withMutualLike_dispatchesMatchNotification() {
        when(actionRepo.findByClientActionId(actorId, clientActionId)).thenReturn(Optional.empty());
        when(actionRepo.findActiveByPair(actorId, targetId)).thenReturn(Optional.empty());
        when(entitlementService.loadEntitlement(actorId)).thenReturn(
                new UserPlanEntitlement(actorId, "FREE", false, 10, 3, 1, 1, 0));
        when(dailyLimitRepo.lockForUpdate(actorId)).thenReturn(
                Optional.of(new DailyLimitRepository.DailyLimitRow(actorId, 0, 0, 0)));
        when(actionRepo.insertAction(actorId, targetId, "SUPERLIKE", clientActionId)).thenReturn(
                new DiscoveryActionRepository.ActionRow(
                        UUID.randomUUID(), actorId, targetId, "SUPERLIKE", "ACTIVE", clientActionId,
                        OffsetDateTime.now()));
        when(actionRepo.findMutualActiveLike(actorId, targetId)).thenReturn(
                Optional.of(new DiscoveryActionRepository.ActionRow(
                        UUID.randomUUID(), targetId, actorId, "LIKE", "ACTIVE", UUID.randomUUID(),
                        OffsetDateTime.now())));
        UUID matchId = UUID.randomUUID();
        when(matchService.tryCreateMatch(actorId, targetId, any(UUID.class), any(UUID.class)))
                .thenReturn(Optional.of(new MatchSummaryDto(matchId, Instant.now(), Instant.now().plusSeconds(300))));

        service.recordSuperLike(actorId, targetId, clientActionId);

        verify(notificationDispatcher).dispatchMatchNotification(eq(actorId), eq(targetId), eq(matchId));
    }
}
