package com.qaliye.backend.matchmaking.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * User-facing row in the caller's introduction history, returned by
 * {@code GET /api/v1/matchmaking/introductions}.
 *
 * <p>Caller-relative fields ({@code role}, {@code yourDecision},
 * {@code partnerDecision}) are resolved server-side. {@code partnerDecision}
 * is masked to {@code "PENDING"} while the caller's own decision is still
 * PENDING so one side's choice cannot influence the other (same rule the
 * detail view applies). {@code partner} carries only the minimal identity
 * fields the history list needs — open the detail endpoint for the full
 * profile.
 */
public record IntroductionSummary(
        UUID id,
        /** The caller's own request id for this introduction. */
        UUID yourRequestId,
        /** REQUESTER or CANDIDATE — which side of the introduction the caller is. */
        String role,
        /** Caller's decision: PENDING / INTERESTED / NOT_INTERESTED. */
        String yourDecision,
        /** Partner's decision — "PENDING" while {@code yourDecision} is PENDING. */
        String partnerDecision,
        /** PROPOSED / DECLINED / MATCHED / CANCELLED / EXPIRED. */
        String status,
        int overallPercentage,
        OffsetDateTime decisionExpiresAt,
        UUID matchId,
        OffsetDateTime proposedAt,
        OffsetDateTime createdAt,
        PartnerSummary partner) {

    /** Minimal partner identity for the history list. */
    public record PartnerSummary(
            String displayName,
            Integer age,
            /** Signed URL of the partner's primary photo. */
            String primaryPhotoUrl) {}
}
