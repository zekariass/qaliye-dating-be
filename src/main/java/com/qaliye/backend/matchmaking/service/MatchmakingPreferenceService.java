package com.qaliye.backend.matchmaking.service;

import com.qaliye.backend.matchmaking.repository.MatchmakingPreferenceRepository;
import com.qaliye.backend.matchmaking.repository.MatchmakingPreferenceRepository.PreferenceRow;
import com.qaliye.backend.matchmaking.repository.MatchmakingPreferenceRepository.PreferenceValues;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Manages user matchmaking preference sets (upsert-on-write, one row per user).
 *
 * <p>{@code gender} is never accepted from the client — the repository derives
 * it as the opposite of {@code profiles.gender} on every write (the sought
 * partner gender; matchmaking is hetero). Enum values are validated
 * here (mirroring the V70 CHECK constraints) so callers get a clean 422
 * instead of a constraint-violation 500.
 */
@Service
public class MatchmakingPreferenceService {

    private final MatchmakingPreferenceRepository preferenceRepo;

    public MatchmakingPreferenceService(MatchmakingPreferenceRepository preferenceRepo) {
        this.preferenceRepo = preferenceRepo;
    }

    /** Returns the caller's preferences, or 404 if none set. */
    public PreferenceRow getPreferences(UUID userId) {
        return preferenceRepo.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Matchmaking preferences not found. Set them first."));
    }

    /** Creates or replaces the caller's preferences; increments version on update. */
    public PreferenceRow upsertPreferences(UUID userId, PreferenceValues values) {
        preferenceRepo.upsert(userId, MatchmakingPreferenceNormalizer.normalize(values));
        return preferenceRepo.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("Preference upsert did not produce a row"));
    }

    /** Admin-only: updates the internal matchmaker notes without touching user-visible fields. */
    public void updateMatchmakerNotes(UUID userId, String notes) {
        boolean updated = preferenceRepo.updateNotes(userId, notes);
        if (!updated) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No preferences found for user " + userId);
        }
    }
}
