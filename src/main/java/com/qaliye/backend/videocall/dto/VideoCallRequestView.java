package com.qaliye.backend.videocall.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Caller-relative view of a video-call request (design §16 state labels).
 * {@code isRequester} orients the role; {@code canCancel}/{@code canAccept}/
 * {@code canJoin} tell the client which actions are currently valid.
 */
public record VideoCallRequestView(
        UUID id,
        UUID matchId,
        String status,
        String callType,
        @JsonProperty("isRequester") boolean isRequester,
        OffsetDateTime requestExpiresAt,
        OffsetDateTime respondedAt,
        boolean canCancel,
        boolean canAccept,
        boolean canJoin,
        boolean requesterJoined,
        boolean responderJoined,
        /**
         * Absolute instant both participants are dropped from the channel —
         * anchored on the first successful join (now + per-call-type cap).
         * {@code null} until anyone joins. Use for the in-call countdown.
         */
        OffsetDateTime callDeadlineAt,
        OffsetDateTime endedAt,
        OffsetDateTime createdAt,
        /**
         * Seconds until the requester may send another remind — {@code 0} when
         * allowed now, {@code null} when reminding isn't possible at all
         * (not PENDING, or caller is the responder).
         */
        Long nextRemindInSeconds) {}
