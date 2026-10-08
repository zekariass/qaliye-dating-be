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
import static org.mockito.ArgumentMatchers.anyLong;
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
    private CreditService creditService;
    private VideoCallRequestService service;

    @BeforeEach
    void setUp() {
        requestRepo = mock(VideoCallRequestRepository.class);
        videoProvider = mock(VideoCallProvider.class);
        notificationDispatcher = mock(NotificationDispatcher.class);
        creditService = mock(CreditService.class);
        VideoProperties videoProps = new VideoProperties();
        videoProps.getCall().setVideoMaxSeconds(1800);
        videoProps.getCall().setAudioMaxSeconds(600);
        videoProps.getCall().setJoinGraceSeconds(120);
        service = new VideoCallRequestService(requestRepo, videoProvider,
                new MatchmakingProperties(), videoProps, notificationDispatcher,
                mock(ActionCostService.class), mock(ActionLimitRepository.class),
                creditService);

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

    // -- No-show sweeper --

    @Test
    void noShowSweep_requesterCharged_responderMissing_partialRefund() {
        // Requester joined 60 s ago and paid 1800 credits (video cap 1800 s);
        // responder never showed → refund 1800 × (1800 − 60) / 1800 = 1740.
        UUID chargeKey = UUID.randomUUID();
        VideoCallRequestRow row = noShowRow(chargeKey, TYPE_VIDEO,
                OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(60), null);
        when(requestRepo.findNoShowCandidates(120)).thenReturn(List.of(row));
        when(requestRepo.markNoShow(REQUEST_ID)).thenReturn(true);
        when(creditService.findConsumedAmount(REQUESTER, "vcr:" + chargeKey))
                .thenReturn(1800L);

        assertThat(service.noShowSweep()).containsExactly(REQUEST_ID);

        ArgumentCaptor<Long> refund = ArgumentCaptor.forClass(Long.class);
        verify(creditService).refundCredits(eq(REQUESTER), refund.capture(),
                eq(REQUEST_ID), eq(VideoCallConstants.ACTION_VIDEO_CALL),
                eq("vcr-refund:" + REQUEST_ID));
        assertThat(refund.getValue()).isBetween(1739L, 1741L);
        verify(notificationDispatcher).dispatchVideoCallNoShow(
                REQUESTER, REQUEST_ID, TYPE_VIDEO);
        verify(notificationDispatcher).dispatchVideoCallNoShow(
                RESPONDER, REQUEST_ID, TYPE_VIDEO);
    }

    @Test
    void noShowSweep_requesterNeverJoined_noChargeNoRefund() {
        // Responder waited; requester never joined → no charge key, nothing
        // to refund. Still closes NO_SHOW and alerts both parties.
        VideoCallRequestRow row = noShowRow(null, TYPE_AUDIO, null,
                OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(90));
        when(requestRepo.findNoShowCandidates(120)).thenReturn(List.of(row));
        when(requestRepo.markNoShow(REQUEST_ID)).thenReturn(true);

        assertThat(service.noShowSweep()).containsExactly(REQUEST_ID);

        verify(creditService, never()).refundCredits(any(), anyLong(),
                any(), any(), any());
        verify(notificationDispatcher).dispatchVideoCallNoShow(
                REQUESTER, REQUEST_ID, TYPE_AUDIO);
        verify(notificationDispatcher).dispatchVideoCallNoShow(
                RESPONDER, REQUEST_ID, TYPE_AUDIO);
    }

    @Test
    void noShowSweep_chargeCoveredByAllowance_noRefund() {
        // Charge key stamped but findConsumedAmount is NULL → the call ran on
        // a free allowance; no credits were deducted so nothing is refunded.
        UUID chargeKey = UUID.randomUUID();
        VideoCallRequestRow row = noShowRow(chargeKey, TYPE_VIDEO,
                OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(60), null);
        when(requestRepo.findNoShowCandidates(120)).thenReturn(List.of(row));
        when(requestRepo.markNoShow(REQUEST_ID)).thenReturn(true);
        when(creditService.findConsumedAmount(REQUESTER, "vcr:" + chargeKey))
                .thenReturn(null);

        service.noShowSweep();

        verify(creditService, never()).refundCredits(any(), anyLong(),
                any(), any(), any());
    }

    @Test
    void noShowSweep_guardedUpdateLost_skipsRefundAndAlerts() {
        UUID chargeKey = UUID.randomUUID();
        VideoCallRequestRow row = noShowRow(chargeKey, TYPE_VIDEO,
                OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(60), null);
        when(requestRepo.findNoShowCandidates(120)).thenReturn(List.of(row));
        when(requestRepo.markNoShow(REQUEST_ID)).thenReturn(false);

        assertThat(service.noShowSweep()).isEmpty();
        verify(creditService, never()).refundCredits(any(), anyLong(),
                any(), any(), any());
        verify(notificationDispatcher, never())
                .dispatchVideoCallNoShow(any(), any(), any());
    }

    // -- Expiry sweeper --

    @Test
    void expireSweep_acceptedNobodyJoined_expiresAndNotifiesBoth() {
        // Accepted but neither party joined → lives until request_expires_at,
        // then EXPIRED (not NO_SHOW); both parties are told it lapsed.
        VideoCallRequestRow row = noShowRow(null, TYPE_VIDEO, null, null);
        when(requestRepo.findExpired()).thenReturn(List.of(row));
        when(requestRepo.expire(REQUEST_ID)).thenReturn(true);

        assertThat(service.expireSweep()).containsExactly(REQUEST_ID);
        verify(notificationDispatcher).dispatchVideoCallExpired(
                REQUESTER, REQUEST_ID, TYPE_VIDEO);
        verify(notificationDispatcher).dispatchVideoCallExpired(
                RESPONDER, REQUEST_ID, TYPE_VIDEO);
        verify(creditService, never()).refundCredits(any(), anyLong(),
                any(), any(), any());
    }

    @Test
    void expireSweep_pendingRequest_notifiesRequesterOnly() {
        VideoCallRequestRow row = row(STATUS_PENDING, TYPE_VIDEO, null);
        when(requestRepo.findExpired()).thenReturn(List.of(row));
        when(requestRepo.expire(REQUEST_ID)).thenReturn(true);

        assertThat(service.expireSweep()).containsExactly(REQUEST_ID);
        verify(notificationDispatcher).dispatchVideoCallExpired(
                REQUESTER, REQUEST_ID, TYPE_VIDEO);
        verify(notificationDispatcher, never()).dispatchVideoCallExpired(
                eq(RESPONDER), any(), any());
    }

    // -- end() refund consistency --

    @Test
    void end_responderNeverJoined_refundsUnusedPortion() {
        // Same policy as the sweep: requester paid, hung up before the
        // responder connected → pro-rata refund, not the full charge.
        UUID chargeKey = UUID.randomUUID();
        VideoCallRequestRow accepted = noShowRow(chargeKey, TYPE_VIDEO,
                OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(60), null);
        when(requestRepo.lockById(REQUEST_ID)).thenReturn(Optional.of(accepted));
        when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(accepted));
        when(creditService.findConsumedAmount(REQUESTER, "vcr:" + chargeKey))
                .thenReturn(1800L);

        service.end(REQUEST_ID, REQUESTER);

        ArgumentCaptor<Long> refund = ArgumentCaptor.forClass(Long.class);
        verify(creditService).refundCredits(eq(REQUESTER), refund.capture(),
                eq(REQUEST_ID), eq(VideoCallConstants.ACTION_VIDEO_CALL),
                eq("vcr-refund:" + REQUEST_ID));
        assertThat(refund.getValue()).isBetween(1739L, 1741L);
    }

    @Test
    void end_bothJoined_normalBillingNoRefund() {
        // A real call happened — early hangup keeps normal billing.
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        VideoCallRequestRow accepted = noShowRow(UUID.randomUUID(), TYPE_VIDEO,
                now.minusSeconds(60), now.minusSeconds(50));
        when(requestRepo.lockById(REQUEST_ID)).thenReturn(Optional.of(accepted));
        when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(accepted));

        service.end(REQUEST_ID, REQUESTER);

        verify(creditService, never()).refundCredits(any(), anyLong(),
                any(), any(), any());
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

    /**
     * ACCEPTED row accepted long enough ago to be past the join grace —
     * controls the charge key and both join stamps independently.
     */
    private static VideoCallRequestRow noShowRow(UUID chargeKey, String callType,
                                                 OffsetDateTime requesterJoinedAt,
                                                 OffsetDateTime responderJoinedAt) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new VideoCallRequestRow(
                REQUEST_ID, MATCH_ID, REQUESTER, RESPONDER,
                STATUS_ACCEPTED, callType,
                now.plusHours(48),          // requestExpiresAt
                now.minusMinutes(5),        // respondedAt — past the grace
                "vcr_" + REQUEST_ID,        // channelName
                chargeKey,
                chargeKey != null ? now.minusMinutes(4) : null, // requesterChargedAt
                requesterJoinedAt,
                responderJoinedAt,
                null,                       // callDeadlineAt — irrelevant here
                null,                       // endedAt
                0, null,                    // reminderCount, lastRemindedAt
                now.minusMinutes(10), now); // createdAt, updatedAt
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
