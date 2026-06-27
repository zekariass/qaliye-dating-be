package com.qaliye.backend.chat;

import com.qaliye.backend.chat.repository.ChatMatchRepository;
import com.qaliye.backend.chat.service.ChatOutboxService;
import com.qaliye.backend.chat.service.MatchLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MatchLifecycleServiceTest {

    @Mock ChatMatchRepository matchRepository;
    @Mock ChatOutboxService outboxService;

    MatchLifecycleService service;

    UUID matchId      = UUID.randomUUID();
    UUID userOneId    = UUID.randomUUID();
    UUID userTwoId    = UUID.randomUUID();
    UUID endedByUser  = userOneId;

    @BeforeEach
    void setUp() {
        service = new MatchLifecycleService(matchRepository, outboxService);
    }

    @Test
    void endMatch_activeMatch_endsAndCreatesOutboxEvents() {
        ChatMatchRepository.MatchRow active = activeMatch();
        when(matchRepository.findByIdForUpdate(matchId)).thenReturn(Optional.of(active));
        when(matchRepository.endMatch(matchId, "USER_UNMATCH", endedByUser))
                .thenReturn(Optional.of(matchId));

        boolean result = service.endMatch(matchId, "USER_UNMATCH", endedByUser);

        assertThat(result).isTrue();
        verify(outboxService).createMatchEndedEvent(eq(matchId), eq("USER_UNMATCH"), any());
        verify(outboxService).createInboxMatchRemovedEvent(eq(matchId), eq(userOneId), any());
        verify(outboxService).createInboxMatchRemovedEvent(eq(matchId), eq(userTwoId), any());
    }

    @Test
    void endMatch_alreadyEnded_isNoOp() {
        ChatMatchRepository.MatchRow ended = new ChatMatchRepository.MatchRow(matchId, userOneId, userTwoId, "ENDED",
                null, null, "USER_UNMATCH", 1L, 0L, 0L, 0L, 0L,
                null, null, null, null, null, null);
        when(matchRepository.findByIdForUpdate(matchId)).thenReturn(Optional.of(ended));

        boolean result = service.endMatch(matchId, "USER_UNMATCH", endedByUser);

        assertThat(result).isFalse();
        verifyNoInteractions(outboxService);
    }

    @Test
    void endMatch_notFound_isNoOp() {
        when(matchRepository.findByIdForUpdate(matchId)).thenReturn(Optional.empty());

        boolean result = service.endMatch(matchId, "USER_UNMATCH", endedByUser);

        assertThat(result).isFalse();
        verifyNoInteractions(outboxService);
    }

    @Test
    void endMatch_dbRaceCondition_activeRowButEndFails_isNoOp() {
        ChatMatchRepository.MatchRow active = activeMatch();
        when(matchRepository.findByIdForUpdate(matchId)).thenReturn(Optional.of(active));
        when(matchRepository.endMatch(matchId, "USER_UNMATCH", endedByUser))
                .thenReturn(Optional.empty());

        boolean result = service.endMatch(matchId, "USER_UNMATCH", endedByUser);

        assertThat(result).isFalse();
        verifyNoInteractions(outboxService);
    }

    @Test
    void endMatchByPair_activeMatchExists_endsIt() {
        ChatMatchRepository.MatchRow active = activeMatch();
        when(matchRepository.findActiveByPairForUpdate(userOneId, userTwoId))
                .thenReturn(Optional.of(active));
        when(matchRepository.findByIdForUpdate(matchId)).thenReturn(Optional.of(active));
        when(matchRepository.endMatch(matchId, "BLOCKED", endedByUser))
                .thenReturn(Optional.of(matchId));

        boolean result = service.endMatchByPair(userOneId, userTwoId, "BLOCKED", endedByUser);

        assertThat(result).isTrue();
    }

    @Test
    void endMatchByPair_noActiveMatch_returnsFalse() {
        when(matchRepository.findActiveByPairForUpdate(userOneId, userTwoId))
                .thenReturn(Optional.empty());

        boolean result = service.endMatchByPair(userOneId, userTwoId, "BLOCKED", endedByUser);

        assertThat(result).isFalse();
        verifyNoInteractions(outboxService);
    }

    private ChatMatchRepository.MatchRow activeMatch() {
        return new ChatMatchRepository.MatchRow(matchId, userOneId, userTwoId, "ACTIVE",
                null, null, null, 1L, 0L, 0L, 0L, 0L,
                null, null, null, null, null, null);
    }
}
