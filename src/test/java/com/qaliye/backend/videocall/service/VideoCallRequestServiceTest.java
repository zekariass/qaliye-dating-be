package com.qaliye.backend.videocall.service;

import com.qaliye.backend.billing.repository.ActionLimitRepository;
import com.qaliye.backend.billing.service.ActionCostService;
import com.qaliye.backend.billing.service.CreditService;
import com.qaliye.backend.matchmaking.config.MatchmakingProperties;
import com.qaliye.backend.notifications.NotificationDispatcher;
import com.qaliye.backend.videocall.VideoCallConstants;
import com.qaliye.backend.videocall.provider.VideoCallProvider;
import com.qaliye.backend.videocall.provider.VideoProperties;
import com.qaliye.backend.videocall.repository.VideoCallRequestRepository;
import com.qaliye.backend.videocall.repository.VideoCallRequestRepository.MatchPair;
import com.qaliye.backend.videocall.repository.VideoCallRequestRepository.VideoCallRequestRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Duration-cap lifecycle tests: deadline anchoring on first join, shared
 * deadline for late joiners / reconnects, post-deadline 409 + completion,
 * and the sweeper transition + alerts.
 */
class VideoCallRequestServiceTest {

    private static final String STATUS_ACCEPTED  = VideoCallConstants.STATUS_ACCEPTED;
    private static final String STATUS_PENDING   = VideoCallConstants.STATUS_PENDING;
    private static final String STATUS_COMPLETED = VideoCallConstants.STATUS_COMPLETED;
    private static final String TYPE_VIDEO       = VideoCallConstants.CALL_TYPE_VIDEO;
    private static final String TYPE_AUDIO       = VideoCallConstants.CALL_TYPE_AUDIO;

    private static final UUID REQUEST_ID  = UUID.randomUUID();
    private static final UUID REQUEST_ID2 = UUID.randomUUID();
    private static final UUID MATCH_ID    = UUID.randomUUID();
    private static final UUID REQUESTER   = UUID.randomUUID();
    private static final UUID RESPONDER   = UUID.randomUUID();
    private static final VideoCallProvider.JoinSession SESSION =
            new VideoCallProvider.JoinSession("vcr_x", "tok", 1L,
                    OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30));

    private VideoCallRequestRepository requestRepo;
    private VideoCallProvider videoProvider;
    private NotificationDispatcher notificationDispatcher;
    private VideoCallRequestService service;

    @BeforeEach
    void setUp() {
        requestRepo = mock(VideoCallRequestRepository.class);
        videoProvider = mock(VideoCallProvider.class);
        notificationDispatcher = mock(NotificationDispatcher.class);
        VideoProperties videoProps = new VideoProperties();
        videoProps.getCall().setVideoMaxSeconds(1800);
        videoProps.getCall().setAudioMaxSeconds(600);
        service = new VideoCallRequestService(requestRepo, videoProvider,
                new MatchmakingProperties(), videoProps, notificationDispatcher,
                mock(ActionCostService.class), mock(ActionLimitRepository.class),
                mock(CreditService.class));

        lenient().when(requestRepo.blockExists(any(), any())).thenReturn(false);
        lenient().when(requestRepo.findMatch(MATCH_ID))
                .thenReturn(Optional.of(new MatchPair(MATCH_ID, REQUESTER, RESPONDER, "ACTIVE")));
        lenient().when(videoProvider.issueJoin(any(), any(), anyBoolean(), any()))
                .thenReturn(SESSION);
    }

    // -- Deadline anchoring --

    @Test
    void join_firstJoin_anchorsDeadlineWithVideoCap() {
        when(requestRepo.lockById(REQUEST_ID))
                .thenReturn(Optional.of(row(STATUS_ACCEPTED, TYPE_VIDEO, null)));

        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);
        service.join(REQUEST_ID, RESPONDER);

        ArgumentCaptor<OffsetDateTime> deadline = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(requestRepo).anchorDeadline(eq(REQUEST_ID), deadline.capture());
        assertThat(deadline.getValue())
                .isAfter(before.plusSeconds(1799))
                .isBefore(before.plusSeconds(1801));

        // Token minted with the same absolute deadline as its notAfter bound.
        verify(videoProvider).issueJoin(eq(REQUEST_ID), eq(RESPONDER), eq(false),
                eq(deadline.getValue()));
        verify(requestRepo).stampResponderJoin(REQUEST_ID);
    }

    @Test
    void join_firstJoin_audioCall_usesAudioCap() {
        when(requestRepo.lockById(REQUEST_ID))
                .thenReturn(Optional.of(row(STATUS_ACCEPTED, TYPE_AUDIO, null)));

        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);
        service.join(REQUEST_ID, RESPONDER);

        ArgumentCaptor<OffsetDateTime> deadline = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(requestRepo).anchorDeadline(eq(REQUEST_ID), deadline.capture());
        assertThat(deadline.getValue())
                .isAfter(before.plusSeconds(599))
                .isBefore(before.plusSeconds(601));
    }

    @Test
    void join_lateJoiner_keepsSharedDeadline() {
        OffsetDateTime anchored = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(25);
        when(requestRepo.lockById(REQUEST_ID))
                .thenReturn(Optional.of(row(STATUS_ACCEPTED, TYPE_VIDEO, anchored)));

        service.join(REQUEST_ID, REQUESTER);

        verify(requestRepo, never()).anchorDeadline(any(), any());
        // Late joiner's token is clamped to the SHARED deadline — remaining
        // time only, not a fresh full window.
        verify(videoProvider).issueJoin(eq(REQUEST_ID), eq(REQUESTER), eq(false), eq(anchored));
    }

    @Test
    void join_reconnectBeforeDeadline_succeedsWithSameDeadline() {
        OffsetDateTime anchored = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10);
        OffsetDateTime firstJoin = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(20);
        when(requestRepo.lockById(REQUEST_ID))
                .thenReturn(Optional.of(row(STATUS_ACCEPTED, TYPE_VIDEO, anchored,
                        null, firstJoin)));

        service.join(REQUEST_ID, RESPONDER); // reconnect / token renewal

        verify(videoProvider).issueJoin(eq(REQUEST_ID), eq(RESPONDER), eq(false), eq(anchored));
        verify(requestRepo, never()).completeTimeLimit(any());
        verify(requestRepo, never()).anchorDeadline(any(), any());
    }

    // -- Post-deadline rejection --

    @Test
    void join_afterDeadline_completesAndThrowsCallDurationExceeded() {
        OffsetDateTime past = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(5);
        when(requestRepo.lockById(REQUEST_ID))
                .thenReturn(Optional.of(row(STATUS_ACCEPTED, TYPE_VIDEO, past)));
        when(requestRepo.completeTimeLimit(REQUEST_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.join(REQUEST_ID, RESPONDER))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getReason()).isEqualTo("CALL_DURATION_EXCEEDED");
                });

        verify(requestRepo).completeTimeLimit(REQUEST_ID);
        verify(notificationDispatcher).dispatchVideoCallEndedTimeLimit(
                REQUESTER, REQUEST_ID, TYPE_VIDEO);
        verify(notificationDispatcher).dispatchVideoCallEndedTimeLimit(
                RESPONDER, REQUEST_ID, TYPE_VIDEO);
        verify(videoProvider, never()).issueJoin(any(), any(), anyBoolean(), any());
    }

    @Test
    void join_afterDeadline_alreadyCompletedBySweeper_stillReturnsDurationCode() {
        OffsetDateTime past = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);
        when(requestRepo.lockById(REQUEST_ID))
                .thenReturn(Optional.of(row(STATUS_COMPLETED, TYPE_VIDEO, past)));
        // Guarded UPDATE loses the race — sweeper already completed it.
        when(requestRepo.completeTimeLimit(REQUEST_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.join(REQUEST_ID, REQUESTER))
                .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                        assertThat(e.getReason()).isEqualTo("CALL_DURATION_EXCEEDED"));

        verify(notificationDispatcher, never())
                .dispatchVideoCallEndedTimeLimit(any(), any(), any());
    }

    @Test
    void join_notAccepted_throwsGenericConflict() {
        when(requestRepo.lockById(REQUEST_ID))
                .thenReturn(Optional.of(row(STATUS_PENDING, TYPE_VIDEO, null)));

        assertThatThrownBy(() -> service.join(REQUEST_ID, RESPONDER))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getReason()).isNotEqualTo("CALL_DURATION_EXCEEDED");
                });
    }

    // -- Deadline sweeper --

    @Test
    void deadlineSweep_completesAndNotifiesBothParties() {
        VideoCallRequestRow video = row(REQUEST_ID, STATUS_ACCEPTED, TYPE_VIDEO,
                OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1), null, null);
        VideoCallRequestRow audio = row(REQUEST_ID2, STATUS_ACCEPTED, TYPE_AUDIO,
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2), null, null);
        when(requestRepo.findDeadlinePassed()).thenReturn(List.of(video, audio));
        when(requestRepo.completeTimeLimit(any())).thenReturn(true);

        List<UUID> ended = service.deadlineSweep();

        assertThat(ended).containsExactly(REQUEST_ID, REQUEST_ID2);
        verify(notificationDispatcher).dispatchVideoCallEndedTimeLimit(
                REQUESTER, REQUEST_ID, TYPE_VIDEO);
        verify(notificationDispatcher).dispatchVideoCallEndedTimeLimit(
                RESPONDER, REQUEST_ID, TYPE_VIDEO);
        verify(notificationDispatcher).dispatchVideoCallEndedTimeLimit(
                REQUESTER, REQUEST_ID2, TYPE_AUDIO);
        verify(notificationDispatcher).dispatchVideoCallEndedTimeLimit(
                RESPONDER, REQUEST_ID2, TYPE_AUDIO);
    }

    @Test
    void deadlineSweep_guardedUpdateLost_skipsNotification() {
        VideoCallRequestRow row = row(STATUS_ACCEPTED, TYPE_VIDEO,
                OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        when(requestRepo.findDeadlinePassed()).thenReturn(List.of(row));
        when(requestRepo.completeTimeLimit(REQUEST_ID)).thenReturn(false);

        assertThat(service.deadlineSweep()).isEmpty();
        verify(notificationDispatcher, never())
                .dispatchVideoCallEndedTimeLimit(any(), any(), any());
    }

    @Test
    void deadlineSweep_nothingOverdue_noop() {
        when(requestRepo.findDeadlinePassed()).thenReturn(List.of());

        assertThat(service.deadlineSweep()).isEmpty();
        verify(requestRepo, never()).completeTimeLimit(any());
    }

    // -- Row factory --

    private static VideoCallRequestRow row(String status, String callType,
                                           OffsetDateTime deadline) {
        return row(REQUEST_ID, status, callType, deadline, null, null);
    }

    private static VideoCallRequestRow row(String status, String callType,
                                           OffsetDateTime deadline,
                                           OffsetDateTime requesterJoinedAt,
                                           OffsetDateTime responderJoinedAt) {
        return row(REQUEST_ID, status, callType, deadline,
                requesterJoinedAt, responderJoinedAt);
    }

    private static VideoCallRequestRow row(UUID id, String status, String callType,
                                           OffsetDateTime deadline,
                                           OffsetDateTime requesterJoinedAt,
                                           OffsetDateTime responderJoinedAt) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new VideoCallRequestRow(
                id, MATCH_ID, REQUESTER, RESPONDER,
                status, callType,
                now.plusHours(48),          // requestExpiresAt
                now.minusMinutes(1),        // respondedAt
                "vcr_" + id,                // channelName
                UUID.randomUUID(),          // requesterChargeKey — already charged → free joins
                now.minusMinutes(1),        // requesterChargedAt
                requesterJoinedAt,
                responderJoinedAt,
                deadline,
                null,                       // endedAt
                0, null,                    // reminderCount, lastRemindedAt
                now.minusMinutes(5), now);  // createdAt, updatedAt
    }
}
