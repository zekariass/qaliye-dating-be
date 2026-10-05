package com.qaliye.backend.matchmaking.dto;

import com.qaliye.backend.matchmaking.repository.MatchmakingPreferenceRepository.PreferenceValues;
import com.qaliye.backend.matchmaking.repository.MatchmakingRequestRepository.RequestRow;
import com.qaliye.backend.matchmaking.service.MatchmakingCompatibilityService.DimensionResult;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Admin candidate-search result: the candidate's matchmaking request, a
 * compact profile card, the candidate's live preference values, and the
 * bidirectional compatibility score so the matchmaker can judge a pairing
 * without cross-referencing other admin endpoints.
 */
public record CandidateMatchView(
        RequestRow request,
        CandidateProfileCard profile,
        PreferenceValues preferences,
        MatchScore match) {

    /**
     * Compact profile card for admin candidate review.
     * Signed photo URL has ~1h TTL — refetch if expired.
     */
    public record CandidateProfileCard(
            UUID userId,
            String displayName,
            Integer age,
            String gender,
            String city,
            String region,
            String countryCode,
            String countryName,
            Boolean isVerified,
            String primaryPhotoUrl,
            OffsetDateTime lastActiveAt) {}

    /**
     * Bidirectional compatibility score for this pairing.
     * {@code forwardPercentage}: requester prefs satisfied by the candidate;
     * {@code reversePercentage}: candidate prefs satisfied by the requester.
     */
    public record MatchScore(
            int forwardPercentage,
            int reversePercentage,
            int overallPercentage,
            List<DimensionResult> details) {}
}
