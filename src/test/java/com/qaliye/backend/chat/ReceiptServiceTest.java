package com.qaliye.backend.chat;

import com.qaliye.backend.chat.exception.InvalidReceiptSequenceException;
import com.qaliye.backend.chat.exception.MatchNotActiveException;
import com.qaliye.backend.chat.repository.ChatMatchRepository;
import com.qaliye.backend.chat.repository.ChatMatchRepository.MatchRow;
import com.qaliye.backend.chat.service.ChatOutboxService;
import com.qaliye.backend.chat.service.MatchAuthorizationService;
import com.qaliye.backend.chat.service.ReceiptService;
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
class ReceiptServiceTest {

    @Mock ChatMatchRepository matchRepository;
    @Mock MatchAuthorizationService authorizationService;
    @Mock ChatOutboxService outboxService;

    ReceiptService service;

    UUID callerId = UUID.randomUUID();
    UUID matchId  = UUID.randomUUID();
    UUID otherId  = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ReceiptService(matchRepository, authorizationService, outboxService);
    }

    @Test
    void markDelivered_monotonicAdvance_updatesAndCreatesEvent() {
        MatchRow match = activeMatch(5L, 2L, 0L, 1L, 0L);
        when(matchRepository.findByIdForUpdate(matchId)).thenReturn(Optional.of(match));
        when(matchRepository.updateDeliveredSequence(matchId, true, 4L)).thenReturn(1);

        service.markDelivered(callerId, matchId, 4L);

        verify(matchRepository).updateDeliveredSequence(matchId, true, 4L);
        verify(outboxService).createReceiptUpdatedEvent(eq(matchId), eq(callerId), eq(4L), anyLong(), any());
    }

    @Test
    void markDelivered_staleSequence_isNoOp() {
        MatchRow match = activeMatch(10L, 5L, 0L, 3L, 0L);
        when(matchRepository.findByIdForUpdate(matchId)).thenReturn(Optional.of(match));

        service.markDelivered(callerId, matchId, 3L);

        verify(matchRepository, never()).updateDeliveredSequence(any(), anyBoolean(), anyLong());
        verifyNoInteractions(outboxService);
    }

    @Test
    void markDelivered_sequenceBeyondNextMessage_throwsInvalid() {
        MatchRow match = activeMatch(5L, 2L, 0L, 1L, 0L);
        when(matchRepository.findByIdForUpdate(matchId)).thenReturn(Optional.of(match));

        assertThatThrownBy(() -> service.markDelivered(callerId, matchId, 99L))
                .isInstanceOf(InvalidReceiptSequenceException.class);
    }

    @Test
    void markRead_advancesDeliveredIfNeeded() {
        MatchRow match = activeMatch(10L, 3L, 0L, 2L, 0L);
        when(matchRepository.findByIdForUpdate(matchId)).thenReturn(Optional.of(match));
        when(matchRepository.updateReadSequence(matchId, true, 5L, 5L)).thenReturn(1);

        service.markRead(callerId, matchId, 5L);

        verify(matchRepository).updateReadSequence(matchId, true, 5L, 5L);
        verify(outboxService).createReceiptUpdatedEvent(eq(matchId), eq(callerId), eq(5L), eq(5L), any());
        verify(outboxService).createInboxMatchUpdatedEvent(eq(matchId), eq(callerId), any());
    }

    @Test
    void markRead_staleSequence_isNoOp() {
        MatchRow match = activeMatch(10L, 5L, 0L, 5L, 0L);
        when(matchRepository.findByIdForUpdate(matchId)).thenReturn(Optional.of(match));

        service.markRead(callerId, matchId, 3L);

        verify(matchRepository, never()).updateReadSequence(any(), anyBoolean(), anyLong(), anyLong());
    }

    @Test
    void markDelivered_endedMatch_throwsMatchNotActive() {
        MatchRow ended = endedMatch();
        when(matchRepository.findByIdForUpdate(matchId)).thenReturn(Optional.of(ended));

        assertThatThrownBy(() -> service.markDelivered(callerId, matchId, 1L))
                .isInstanceOf(MatchNotActiveException.class);
    }

    private ChatMatchRepository.MatchRow activeMatch(long nextSeq, long u1Delivered, long u2Delivered,
                                  long u1Read, long u2Read) {
        return new ChatMatchRepository.MatchRow(matchId, callerId, otherId, "ACTIVE",
                null, null, null, nextSeq,
                u1Delivered, u2Delivered, u1Read, u2Read,
                null, null, null, null, null, null);
    }

    private ChatMatchRepository.MatchRow endedMatch() {
        return new ChatMatchRepository.MatchRow(matchId, callerId, otherId, "ENDED",
                null, null, "USER_UNMATCH", 3L,
                0L, 0L, 0L, 0L, null, null, null, null, null, null);
    }
}
