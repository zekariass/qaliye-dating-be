package com.qaliye.backend.matchmaking.dto;

import com.qaliye.backend.profile.dto.OtherUserProfileDto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * User-facing view of a matchmaking introduction returned by
 * {@code GET /api/v1/matchmaking/introductions/{id}}.
 *
 * <p>Flattens the introduction row (admin-internal fields such as
 * initiated_by_admin_user_id are deliberately omitted) and resolves the
 * caller-relative fields so the client does not have to figure out which
 * side it is. {@code partner} carries the proposed user's full profile
 * (photos, bio, address, lifestyle fields) — the data the decision screen
 * needs to render.
 */
public record IntroductionView(
        UUID id,
        UUID requesterRequestId,
        UUID candidateRequestId,
        /** REQUESTER or CANDIDATE — which side of the introduction the caller is. */
        String role,
        /** The caller's own request id. */
        UUID yourRequestId,
        /** Caller's decision: PENDING / INTERESTED / NOT_INTERESTED. */
        String yourDecision,
        /** The other participant's decision (same value domain). */
        String partnerDecision,
        String status,
        /** Bidirectional compatibility score — how well caller's prefs match partner. */
        int forwardPercentage,
        /** How well partner's prefs match the caller. */
        int reversePercentage,
        /** Overall compatibility score (min of forward/reverse). */
        int overallPercentage,
        OffsetDateTime decisionExpiresAt,
        OffsetDateTime requesterDecidedAt,
        OffsetDateTime candidateDecidedAt,
        UUID matchId,
        OffsetDateTime proposedAt,
        OffsetDateTime completedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        /** Full profile of the proposed partner (photos included). */
        OtherUserProfileDto partner) {
}
