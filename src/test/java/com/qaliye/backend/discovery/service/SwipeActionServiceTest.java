package com.qaliye.backend.discovery.service;

import com.qaliye.backend.billing.repository.ActionFeatureVariantRepository;
import com.qaliye.backend.billing.repository.ActionLimitRepository;
import com.qaliye.backend.billing.service.ActionCostService;
import com.qaliye.backend.billing.service.CreditService;
import com.qaliye.backend.discovery.dto.MatchSummaryDto;
import com.qaliye.backend.discovery.dto.SwipeActionResponse;
import com.qaliye.backend.discovery.exception.ActionLimitExceededException;
import com.qaliye.backend.discovery.exception.DuplicateActiveActionException;
import com.qaliye.backend.discovery.exception.InvalidLikeVariantException;
import com.qaliye.backend.discovery.exception.TargetIneligibleException;
import com.qaliye.backend.discovery.repository.DiscoveryActionRepository;
import com.qaliye.backend.chat.service.MatchLifecycleService;
import com.qaliye.backend.notifications.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
    @Mock ActionCostService actionCostService;
    @Mock ActionLimitRepository actionLimitRepo;
    @Mock ActionFeatureVariantRepository variantRepo;
    @Mock CreditService creditService;
    @Mock MatchService matchService;
    @Mock NotificationDispatcher notificationDispatcher;
    @Mock MatchLifecycleService matchLifecycleService;
    @Mock NamedParameterJdbcTemplate jdbc;

    SwipeActionService service;

    UUID actorId = UUID.randomUUID();
    UUID targetId = UUID.randomUUID();
    UUID clientActionId = UUID.randomUUID();
    UUID heartVariantId = UUID.randomUUID();

    ActionFeatureVariantRepository.VariantRow heartVariant = new ActionFeatureVariantRepository.VariantRow(
            heartVariantId, UUID.randomUUID(), "LIKE", "HEART", "Heart", "Strong feelings",
            "https://cdn.qal.app/actions/heart.webp", true, true, 1);

    @BeforeEach
    void setUp() {
        service = new SwipeActionService(
                actionRepo, actionCostService, actionLimitRepo, variantRepo, creditService,
                matchService, notificationDispatcher, matchLifecycleService, jdbc);
    }

    private void mockTargetEligible() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(ResultSetExtractor.class))).thenReturn(true);
        when(jdbc.queryForList(contains("profile_photos"), any(SqlParameterSource.class))).thenReturn(List.of(Map.of()));
        when(jdbc.queryForList(contains("user_blocks"), any(SqlParameterSource.class))).thenReturn(List.of());
    }

    private void mockHeartVariant() {
        when(variantRepo.findByActionCodeAndVariantCode("LIKE", "HEART")).thenReturn(Optional.of(heartVariant));
    }

    private ActionCostService.VariantCostResult freeVariantCost() {
        return new ActionCostService.VariantCostResult(null, false, 0, true, false, false,
                java.time.LocalDate.now(), java.time.LocalDate.now(), 0, null, "DAY");
    }

    @Test
    void recordLike_withMutualLike_dispatchesMatchNotification() {
        mockTargetEligible();
        mockHeartVariant();
        when(actionRepo.findByClientActionId(actorId, clientActionId)).thenReturn(Optional.empty());
        when(actionRepo.findActiveByPair(actorId, targetId)).thenReturn(Optional.empty());
        when(actionCostService.evaluateVariant(eq(actorId), eq("LIKE"), eq(heartVariantId))).thenReturn(freeVariantCost());
        when(actionRepo.insertAction(actorId, targetId, "LIKE", clientActionId, "HEART")).thenReturn(
                new DiscoveryActionRepository.ActionRow(
                        UUID.randomUUID(), actorId, targetId, "LIKE", "ACTIVE", clientActionId,
                        OffsetDateTime.now(), "HEART"));
        when(actionRepo.findMutualActiveLike(actorId, targetId)).thenReturn(
                Optional.of(new DiscoveryActionRepository.ActionRow(
                        UUID.randomUUID(), targetId, actorId, "LIKE", "ACTIVE", UUID.randomUUID(),
                        OffsetDateTime.now(), "HEART")));
        UUID matchId = UUID.randomUUID();
        when(matchService.tryCreateMatch(eq(actorId), eq(targetId), any(UUID.class), any(UUID.class)))
                .thenReturn(Optional.of(new MatchSummaryDto(matchId, "DISCOVERY", Instant.now(), Instant.now().plusSeconds(300), null)));

        service.recordLike(actorId, targetId, clientActionId, "HEART");

        ArgumentCaptor<UUID> userOneCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<UUID> userTwoCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<UUID> matchIdCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(notificationDispatcher).dispatchMatchNotification(
                userOneCaptor.capture(), userTwoCaptor.capture(), matchIdCaptor.capture());

        assertThat(userOneCaptor.getValue()).isEqualTo(actorId);
        assertThat(userTwoCaptor.getValue()).isEqualTo(targetId);
        assertThat(matchIdCaptor.getValue()).isEqualTo(matchId);
        verify(notificationDispatcher).dispatchLikeNotification(eq(actorId), eq(targetId), any(UUID.class));
    }

    @Test
    void recordLike_withoutMutualLike_doesNotDispatchNotification() {
        mockTargetEligible();
        mockHeartVariant();
        when(actionRepo.findByClientActionId(actorId, clientActionId)).thenReturn(Optional.empty());
        when(actionRepo.findActiveByPair(actorId, targetId)).thenReturn(Optional.empty());
        when(actionCostService.evaluateVariant(eq(actorId), eq("LIKE"), eq(heartVariantId))).thenReturn(freeVariantCost());
        when(actionRepo.insertAction(actorId, targetId, "LIKE", clientActionId, "HEART")).thenReturn(
                new DiscoveryActionRepository.ActionRow(
                        UUID.randomUUID(), actorId, targetId, "LIKE", "ACTIVE", clientActionId,
                        OffsetDateTime.now(), "HEART"));
        when(actionRepo.findMutualActiveLike(actorId, targetId)).thenReturn(Optional.empty());

        service.recordLike(actorId, targetId, clientActionId, "HEART");

        verify(notificationDispatcher, never()).dispatchMatchNotification(any(), any(), any());
        verify(notificationDispatcher).dispatchLikeNotification(eq(actorId), eq(targetId), any(UUID.class));
    }

    @Test
    void recordLike_missingVariantCode_throwsInvalidLikeVariant() {
        when(actionRepo.findByClientActionId(actorId, clientActionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordLike(actorId, targetId, clientActionId, null))
                .isInstanceOf(InvalidLikeVariantException.class);
    }

    @Test
    void recordLike_unknownVariantCode_throwsInvalidLikeVariant() {
        when(actionRepo.findByClientActionId(actorId, clientActionId)).thenReturn(Optional.empty());
        when(variantRepo.findByActionCodeAndVariantCode("LIKE", "UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordLike(actorId, targetId, clientActionId, "UNKNOWN"))
                .isInstanceOf(InvalidLikeVariantException.class);
    }

    @Test
    void recordLike_inactiveVariantCode_throwsInvalidLikeVariant() {
        ActionFeatureVariantRepository.VariantRow inactiveVariant = new ActionFeatureVariantRepository.VariantRow(
                UUID.randomUUID(), UUID.randomUUID(), "LIKE", "RING", "Ring", "I'm serious",
                "https://cdn.qal.app/actions/ring.webp", false, false, 6);
        when(actionRepo.findByClientActionId(actorId, clientActionId)).thenReturn(Optional.empty());
        when(variantRepo.findByActionCodeAndVariantCode("LIKE", "RING")).thenReturn(Optional.of(inactiveVariant));

        assertThatThrownBy(() -> service.recordLike(actorId, targetId, clientActionId, "RING"))
                .isInstanceOf(InvalidLikeVariantException.class);
    }

    @Test
    void recordSuperLike_withMutualLike_dispatchesMatchNotification() {
        mockTargetEligible();
        when(actionRepo.findByClientActionId(actorId, clientActionId)).thenReturn(Optional.empty());
        when(actionRepo.findActiveByPair(actorId, targetId)).thenReturn(Optional.empty());
        when(actionCostService.evaluate(eq(actorId), eq("SUPER_LIKE"))).thenReturn(
                new ActionCostService.ActionCostResult(null, 0, true, false, false,
                        java.time.LocalDate.now(), java.time.LocalDate.now(), 0, null, "DAY"));
        when(actionRepo.insertAction(actorId, targetId, "SUPERLIKE", clientActionId)).thenReturn(
                new DiscoveryActionRepository.ActionRow(
                        UUID.randomUUID(), actorId, targetId, "SUPERLIKE", "ACTIVE", clientActionId,
                        OffsetDateTime.now(), null));
        when(actionRepo.findMutualActiveLike(actorId, targetId)).thenReturn(
                Optional.of(new DiscoveryActionRepository.ActionRow(
                        UUID.randomUUID(), targetId, actorId, "LIKE", "ACTIVE", UUID.randomUUID(),
                        OffsetDateTime.now(), "HEART")));
        UUID matchId = UUID.randomUUID();
        when(matchService.tryCreateMatch(eq(actorId), eq(targetId), any(UUID.class), any(UUID.class)))
                .thenReturn(Optional.of(new MatchSummaryDto(matchId, "DISCOVERY", Instant.now(), Instant.now().plusSeconds(300), null)));

        service.recordSuperLike(actorId, targetId, clientActionId);

        verify(notificationDispatcher).dispatchMatchNotification(eq(actorId), eq(targetId), eq(matchId));
        verify(notificationDispatcher).dispatchSuperLikeNotification(eq(actorId), eq(targetId), any(UUID.class));
    }

    @Test
    void recordLike_afterUnmatch_canReLikeAndRematch() {
        UUID newClientActionId = UUID.randomUUID();
        mockTargetEligible();
        mockHeartVariant();
        when(actionRepo.findByClientActionId(actorId, newClientActionId)).thenReturn(Optional.empty());
        when(actionRepo.findActiveByPair(actorId, targetId)).thenReturn(Optional.empty());
        when(actionCostService.evaluateVariant(eq(actorId), eq("LIKE"), eq(heartVariantId))).thenReturn(freeVariantCost());
        UUID newActionId = UUID.randomUUID();
        when(actionRepo.insertAction(actorId, targetId, "LIKE", newClientActionId, "HEART")).thenReturn(
                new DiscoveryActionRepository.ActionRow(
                        newActionId, actorId, targetId, "LIKE", "ACTIVE", newClientActionId,
                        OffsetDateTime.now(), "HEART"));
        when(actionRepo.findMutualActiveLike(actorId, targetId)).thenReturn(
                Optional.of(new DiscoveryActionRepository.ActionRow(
                        UUID.randomUUID(), targetId, actorId, "LIKE", "ACTIVE", UUID.randomUUID(),
                        OffsetDateTime.now(), "HEART")));
        UUID newMatchId = UUID.randomUUID();
        when(matchService.tryCreateMatch(eq(actorId), eq(targetId), eq(newActionId), any(UUID.class)))
                .thenReturn(Optional.of(new MatchSummaryDto(newMatchId, "DISCOVERY", Instant.now(), Instant.now().plusSeconds(300), null)));

        SwipeActionResponse response = service.recordLike(actorId, targetId, newClientActionId, "HEART");

        assertThat(response.isMatch()).isTrue();
        assertThat(response.match()).isNotNull();
        assertThat(response.match().matchId()).isEqualTo(newMatchId);
        verify(notificationDispatcher).dispatchMatchNotification(eq(actorId), eq(targetId), eq(newMatchId));
    }
}
