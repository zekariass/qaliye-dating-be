package com.qaliye.backend.matchmaking.controller;

import com.qaliye.backend.common.CallerUtils;
import com.qaliye.backend.matchmaking.dto.IntroductionSummary;
import com.qaliye.backend.matchmaking.dto.IntroductionView;
import com.qaliye.backend.matchmaking.repository.MatchmakingIntroductionRepository.IntroductionRow;
import com.qaliye.backend.matchmaking.repository.MatchmakingPreferenceRepository.PreferenceRow;
import com.qaliye.backend.matchmaking.repository.MatchmakingPreferenceRepository.PreferenceValues;
import com.qaliye.backend.matchmaking.repository.MatchmakingRequestRepository.RequestRow;
import com.qaliye.backend.matchmaking.service.MatchmakingIntroductionService;
import com.qaliye.backend.matchmaking.service.MatchmakingPreferenceService;
import com.qaliye.backend.matchmaking.service.MatchmakingRequestService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * User-facing matchmaking endpoints.
 *
 * Base path: {@code /api/v1/matchmaking}
 */
@RestController
@RequestMapping("/api/v1/matchmaking")
public class MatchmakingController {

    private final MatchmakingPreferenceService preferenceService;
    private final MatchmakingRequestService requestService;
    private final MatchmakingIntroductionService introductionService;

    public MatchmakingController(MatchmakingPreferenceService preferenceService,
                                 MatchmakingRequestService requestService,
                                 MatchmakingIntroductionService introductionService) {
        this.preferenceService = preferenceService;
        this.requestService = requestService;
        this.introductionService = introductionService;
    }

    // ── Preferences ──────────────────────────────────────────────────────

    @GetMapping("/preferences")
    public ResponseEntity<PreferenceRow> getPreferences() {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(preferenceService.getPreferences(callerId));
    }

    @PutMapping("/preferences")
    public ResponseEntity<PreferenceRow> upsertPreferences(
            @RequestBody @Valid PreferenceValues body) {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(preferenceService.upsertPreferences(callerId, body));
    }

    // ── Requests ─────────────────────────────────────────────────────────

    @PostMapping("/requests")
    public ResponseEntity<RequestRow> createRequest(
            @RequestBody @Valid CreateRequestBody body) {
        UUID callerId = CallerUtils.callerId();
        RequestRow created = requestService.createRequest(callerId, body.chargeIdempotencyKey());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/requests")
    public ResponseEntity<List<RequestRow>> getMyRequests(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(requestService.getMyRequests(callerId, page, size));
    }

    @GetMapping("/requests/active")
    public ResponseEntity<RequestRow> getActiveRequest() {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(requestService.getActiveRequest(callerId));
    }

    @GetMapping("/requests/{requestId}")
    public ResponseEntity<RequestRow> getRequest(
            @PathVariable UUID requestId) {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(requestService.getRequest(requestId, callerId));
    }

    @DeleteMapping("/requests/{requestId}")
    public ResponseEntity<Void> cancelRequest(
            @PathVariable UUID requestId) {
        UUID callerId = CallerUtils.callerId();
        requestService.cancelRequest(requestId, callerId);
        return ResponseEntity.noContent().build();
    }

    // ── Introductions ────────────────────────────────────────────────────

    @GetMapping("/introductions")
    public ResponseEntity<List<IntroductionSummary>> getMyIntroductions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(introductionService.getMyIntroductions(callerId, page, size));
    }

    @GetMapping("/introductions/{introductionId}")
    public ResponseEntity<IntroductionView> getIntroduction(
            @PathVariable UUID introductionId) {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(introductionService.getIntroductionView(introductionId, callerId));
    }

    @PostMapping("/introductions/{introductionId}/decision")
    public ResponseEntity<IntroductionRow> recordDecision(
            @PathVariable UUID introductionId,
            @RequestBody @Valid DecisionBody body) {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(introductionService.recordDecision(introductionId, callerId, body.decision()));
    }

    // ── Request / Response records ────────────────────────────────────────

    public record CreateRequestBody(@NotNull UUID chargeIdempotencyKey) {}

    public record DecisionBody(@NotBlank String decision) {}
}
