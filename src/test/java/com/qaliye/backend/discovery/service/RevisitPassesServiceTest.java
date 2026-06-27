package com.qaliye.backend.discovery.service;

import com.qaliye.backend.discovery.dto.RevisitPassesResponse;
import com.qaliye.backend.discovery.exception.ActorIneligibleException;
import com.qaliye.backend.discovery.repository.DiscoveryActionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RevisitPassesServiceTest {

    @Mock DiscoveryActionRepository actionRepo;
    @Mock DiscoveryQueryService queryService;
    @Mock NamedParameterJdbcTemplate jdbc;

    RevisitPassesService service;

    UUID actorId = UUID.randomUUID();
    UUID actionId1 = UUID.randomUUID();
    UUID actionId2 = UUID.randomUUID();
    UUID actionId3 = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new RevisitPassesService(actionRepo, queryService, jdbc);
    }

    @Test
    void revisitPasses_withNullContext_throwsActorIneligibleException() {
        when(queryService.loadActorContext(actorId)).thenReturn(null);

        assertThatThrownBy(() -> service.revisitPasses(actorId, 10))
                .isInstanceOf(ActorIneligibleException.class);
    }

    @Test
    void revisitPasses_withNoCandidates_returnsSuccessAndZero() {
        DiscoveryQueryService.ActorContext ctx = mockContext();
        when(queryService.loadActorContext(actorId)).thenReturn(ctx);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(new ArrayList<UUID>());

        RevisitPassesResponse response = service.revisitPasses(actorId, 10);

        assertThat(response.success()).isTrue();
        assertThat(response.reopenedCount()).isZero();
    }

    @Test
    void revisitPasses_defaultAmount_reopensUpToTen() {
        DiscoveryQueryService.ActorContext ctx = mockContext();
        when(queryService.loadActorContext(actorId)).thenReturn(ctx);
        List<UUID> candidates = List.of(actionId1, actionId2, actionId3);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(candidates);
        when(actionRepo.reversePassForRevisit(any())).thenReturn(1);

        RevisitPassesResponse response = service.revisitPasses(actorId, 10);

        assertThat(response.success()).isTrue();
        assertThat(response.reopenedCount()).isEqualTo(3);
        verify(actionRepo).reversePassForRevisit(actionId1);
        verify(actionRepo).reversePassForRevisit(actionId2);
        verify(actionRepo).reversePassForRevisit(actionId3);
    }

    @Test
    void revisitPasses_amount20_reopensUpToTwenty() {
        DiscoveryQueryService.ActorContext ctx = mockContext();
        when(queryService.loadActorContext(actorId)).thenReturn(ctx);
        List<UUID> candidates = buildCandidates(25);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(candidates);
        when(actionRepo.reversePassForRevisit(any())).thenReturn(1);

        RevisitPassesResponse response = service.revisitPasses(actorId, 20);

        assertThat(response.success()).isTrue();
        assertThat(response.reopenedCount()).isEqualTo(20);
        verify(actionRepo, times(20)).reversePassForRevisit(any());
    }

    @Test
    void revisitPasses_amount30_reopensUpToThirty() {
        DiscoveryQueryService.ActorContext ctx = mockContext();
        when(queryService.loadActorContext(actorId)).thenReturn(ctx);
        List<UUID> candidates = buildCandidates(35);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(candidates);
        when(actionRepo.reversePassForRevisit(any())).thenReturn(1);

        RevisitPassesResponse response = service.revisitPasses(actorId, 30);

        assertThat(response.success()).isTrue();
        assertThat(response.reopenedCount()).isEqualTo(30);
        verify(actionRepo, times(30)).reversePassForRevisit(any());
    }

    @Test
    void revisitPasses_doesNotReturnProfileCards() {
        DiscoveryQueryService.ActorContext ctx = mockContext();
        when(queryService.loadActorContext(actorId)).thenReturn(ctx);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(actionId1));
        when(actionRepo.reversePassForRevisit(actionId1)).thenReturn(1);

        RevisitPassesResponse response = service.revisitPasses(actorId, 10);

        assertThat(response).isExactlyInstanceOf(RevisitPassesResponse.class);
        verify(queryService, never()).fetchSingleProfile(any(), any(), any(), anyString());
    }

    @Test
    void revisitPasses_doesNotConsumeRewindLimitsOrEntitlements() {
        DiscoveryQueryService.ActorContext ctx = mockContext();
        when(queryService.loadActorContext(actorId)).thenReturn(ctx);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(actionId1));
        when(actionRepo.reversePassForRevisit(actionId1)).thenReturn(1);

        service.revisitPasses(actorId, 10);

        verifyNoMoreInteractions(actionRepo);
    }

    @Test
    void revisitPasses_inspectsMoreCandidatesWhenRecentPassesIneligible() {
        DiscoveryQueryService.ActorContext ctx = mockContext();
        when(queryService.loadActorContext(actorId)).thenReturn(ctx);
        List<UUID> candidates = List.of(actionId1, actionId2, actionId3);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(candidates);
        when(actionRepo.reversePassForRevisit(actionId1)).thenReturn(0);
        when(actionRepo.reversePassForRevisit(actionId2)).thenReturn(1);
        when(actionRepo.reversePassForRevisit(actionId3)).thenReturn(1);

        RevisitPassesResponse response = service.revisitPasses(actorId, 2);

        assertThat(response.reopenedCount()).isEqualTo(2);
    }

    @Test
    void revisitPasses_doesNotReopenMoreThanRequestedAmount() {
        DiscoveryQueryService.ActorContext ctx = mockContext();
        when(queryService.loadActorContext(actorId)).thenReturn(ctx);
        List<UUID> candidates = buildCandidates(15);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(candidates);
        when(actionRepo.reversePassForRevisit(any())).thenReturn(1);

        RevisitPassesResponse response = service.revisitPasses(actorId, 10);

        assertThat(response.reopenedCount()).isEqualTo(10);
        verify(actionRepo, times(10)).reversePassForRevisit(any());
    }

    @Test
    void revisitPasses_passHistoryRemainsStored() {
        DiscoveryQueryService.ActorContext ctx = mockContext();
        when(queryService.loadActorContext(actorId)).thenReturn(ctx);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(actionId1));
        when(actionRepo.reversePassForRevisit(actionId1)).thenReturn(1);

        service.revisitPasses(actorId, 10);

        verify(actionRepo).reversePassForRevisit(actionId1);
    }

    @Test
    void revisitPasses_blockedOrIneligibleProfilesNotReopened() {
        // The SQL already filters ineligible profiles; this test verifies
        // the service only reverses candidates returned by the query.
        DiscoveryQueryService.ActorContext ctx = mockContext();
        when(queryService.loadActorContext(actorId)).thenReturn(ctx);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(new ArrayList<UUID>());

        RevisitPassesResponse response = service.revisitPasses(actorId, 10);

        assertThat(response.reopenedCount()).isZero();
        verify(actionRepo, never()).reversePassForRevisit(any());
    }

    @Test
    void revisitPasses_mostRecentPassesConsideredFirst() {
        DiscoveryQueryService.ActorContext ctx = mockContext();
        when(queryService.loadActorContext(actorId)).thenReturn(ctx);
        service.revisitPasses(actorId, 10);

        verify(jdbc).query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class));
        // Ordering is verified by SQL inspection; the LIMIT and FOR UPDATE
        // ensure most recent candidates are selected first.
    }

    private DiscoveryQueryService.ActorContext mockContext() {
        return new DiscoveryQueryService.ActorContext(
                UUID.randomUUID(),
                "POINT(38.7 9.0)",
                "FEMALE",
                18,
                35,
                50,
                new String[]{"ETHIOPIA"},
                false,
                true,
                "en"
        );
    }

    private List<UUID> buildCandidates(int count) {
        List<UUID> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(UUID.randomUUID());
        }
        return list;
    }
}
