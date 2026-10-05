package com.qaliye.backend.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

public record InboxItemDto(
        UUID matchId,
        String status,
        String matchSource,
        ParticipantDto participant,
        LastMessageDto lastMessage,
        int unreadCount,
        Instant mutedUntil,
        Instant matchedAt,
        Instant lastMessageAt,
        VideoCallRequestSummary videoCallRequest
) {

    /**
     * Live (PENDING/ACCEPTED) video-call request on this match — null when
     * none exists. {@code canAccept}/{@code canCancel}/{@code canJoin} are
     * caller-relative action flags matching VideoCallRequestView semantics.
     */
    public record VideoCallRequestSummary(
            UUID id,
            String status,
            String callType,
            @JsonProperty("isRequester") boolean isRequester,
            boolean canAccept,
            boolean canCancel,
            boolean canJoin) {}
}
