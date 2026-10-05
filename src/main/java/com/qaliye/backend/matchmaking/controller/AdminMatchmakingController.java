package com.qaliye.backend.matchmaking.controller;

import com.qaliye.backend.matchmaking.repository.MatchmakingAdminNoteRepository.NoteRow;
import com.qaliye.backend.matchmaking.repository.MatchmakingIntroductionRepository.IntroductionRow;
import com.qaliye.backend.matchmaking.repository.MatchmakingRequestRepository.RequestRow;
import com.qaliye.backend.matchmaking.dto.CandidateMatchView;
import com.qaliye.backend.matchmaking.service.MatchmakingAdminDashboardService;
import com.qaliye.backend.matchmaking.service.MatchmakingAdminNoteService;
import com.qaliye.backend.matchmaking.service.MatchmakingCandidateSearchService;
import com.qaliye.backend.matchmaking.service.MatchmakingIntroductionService;
import com.qaliye.backend.matchmaking.service.MatchmakingPreferenceService;
import com.qaliye.backend.matchmaking.service.MatchmakingRequestService;
import com.qaliye.backend.user.UserStatusService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin-only matchmaking management endpoints.
 *
 * Base path: {@code /api/v1/admin/matchmaking}
 */
@RestController
@RequestMapping("/api/v1/admin/matchmaking")
public class AdminMatchmakingController {

    private final MatchmakingRequestService requestService;
    private final MatchmakingPreferenceService preferenceService;
    private final MatchmakingAdminNoteService adminNoteService;
    private final MatchmakingCandidateSearchService candidateSearchService;
    private final MatchmakingIntroductionService introductionService;
    private final MatchmakingAdminDashboardService dashboardService;
    private final UserStatusService userStatusService;

    public AdminMatchmakingController(MatchmakingRequestService requestService,
                                      MatchmakingPreferenceService preferenceService,
                                      MatchmakingAdminNoteService adminNoteService,
                                      MatchmakingCandidateSearchService candidateSearchService,
                                      MatchmakingIntroductionService introductionService,
                                      MatchmakingAdminDashboardService dashboardService,
                                      UserStatusService userStatusService) {
        this.requestService = requestService;
        this.preferenceService = preferenceService;
        this.adminNoteService = adminNoteService;
        this.candidateSearchService = candidateSearchService;
        this.introductionService = introductionService;
        this.dashboardService = dashboardService;
        this.userStatusService = userStatusService;
    }

    // ── Request queue ────────────────────────────────────────────────────

    /**
     * Request queue, optionally filtered by status. Omit {@code status} for
     * ALL requests; pass one of OPEN | ON_HOLD | MATCHED | COMPLETED |
     * CANCELLED | EXPIRED to filter.
     */
    @GetMapping("/requests")
    public ResponseEntity<Map<String, Object>> getQueue(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        requireAdmin(jwt);
        return ResponseEntity.ok(requestService.getQueue(status, page, size));
    }

    @GetMapping("/requests/{requestId}")
    public ResponseEntity<RequestRow> getRequest(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID requestId) {
        requireAdmin(jwt);
        return ResponseEntity.ok(requestService.getRequestAdmin(requestId));
    }

    @DeleteMapping("/requests/{requestId}")
    public ResponseEntity<Void> cancelRequest(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID requestId) {
        requireAdmin(jwt);
        requestService.cancelRequestAdmin(requestId);
        return ResponseEntity.noContent().build();
    }

    // ── Preferences ──────────────────────────────────────────────────────

    @PatchMapping("/users/{userId}/matchmaking-preferences/notes")
    public ResponseEntity<Void> updateMatchmakerNotes(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID userId,
            @RequestBody @Valid UpdateNotesBody body) {
        requireAdmin(jwt);
        preferenceService.updateMatchmakerNotes(userId, body.notes());
        return ResponseEntity.ok().build();
    }

    // ── Admin notes on requests ──────────────────────────────────────────

    @GetMapping("/requests/{requestId}/notes")
    public ResponseEntity<List<NoteRow>> getNotes(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID requestId) {
        requireAdmin(jwt);
        return ResponseEntity.ok(adminNoteService.getNotes(requestId));
    }

    @PostMapping("/requests/{requestId}/notes")
    public ResponseEntity<NoteRow> addNote(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID requestId,
            @RequestBody @Valid AddNoteBody body) {
        requireAdmin(jwt);
        UUID adminId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(adminNoteService.addNote(requestId, adminId, body.note()));
    }

    // ── Candidate search ─────────────────────────────────────────────────

    /**
     * Scored candidate search. Each result carries the candidate's request
     * row, a compact profile card, their live preferences, and the
     * bidirectional match score — ranked by overall percentage.
     */
    @PostMapping("/requests/{requestId}/candidate-search")
    public ResponseEntity<List<CandidateMatchView>> searchCandidates(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID requestId,
            @RequestBody(required = false) CandidateSearchBody body) {
        requireAdmin(jwt);
        Map<String, Object> overrideParams = (body != null) ? body.overrideParams() : null;
        int page = (body != null && body.page() != null) ? body.page() : 0;
        int size = (body != null && body.size() != null) ? body.size() : 20;
        return ResponseEntity.ok(
                candidateSearchService.searchCandidates(requestId, overrideParams, page, size));
    }

    // ── Introductions ────────────────────────────────────────────────────

    /**
     * Global introductions list (dashboard), newest first.
     * Filters: {@code status} (PROPOSED | DECLINED | MATCHED | CANCELLED | EXPIRED),
     * {@code userId} (either participant), {@code search} (display-name substring).
     */
    @GetMapping("/introductions")
    public ResponseEntity<Map<String, Object>> listIntroductions(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        requireAdmin(jwt);
        return ResponseEntity.ok(
                introductionService.listIntroductionsAdmin(status, userId, search, page, size));
    }

    /**
     * Funnel stats for the matchmaking dashboard.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats(
            @AuthenticationPrincipal Jwt jwt) {
        requireAdmin(jwt);
        return ResponseEntity.ok(dashboardService.getStats());
    }

    @PostMapping("/introductions")
    public ResponseEntity<IntroductionRow> createIntroduction(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody @Valid CreateIntroductionBody body) {
        requireAdmin(jwt);
        UUID adminId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(
                introductionService.createIntroduction(
                        body.requesterRequestId(),
                        body.candidateRequestId(),
                        adminId,
                        body.reason()));
    }

    @GetMapping("/introductions/{introductionId}")
    public ResponseEntity<IntroductionRow> getIntroduction(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID introductionId) {
        requireAdmin(jwt);
        return ResponseEntity.ok(introductionService.getIntroductionAdmin(introductionId));
    }

    @GetMapping("/requests/{requestId}/introductions")
    public ResponseEntity<List<IntroductionRow>> getIntroductionsForRequest(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID requestId) {
        requireAdmin(jwt);
        return ResponseEntity.ok(introductionService.getIntroductionsForRequest(requestId));
    }

    @DeleteMapping("/introductions/{introductionId}")
    public ResponseEntity<Void> cancelIntroduction(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID introductionId) {
        requireAdmin(jwt);
        introductionService.cancelIntroduction(introductionId);
        return ResponseEntity.noContent().build();
    }

    // ── Admin guard ───────────────────────────────────────────────────────

    private void requireAdmin(Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        UserStatusService.UserStatus status = userStatusService.getStatus(userId);
        if (status == null || !"ADMIN".equals(status.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required.");
        }
    }

    // ── Request / Response records ────────────────────────────────────────

    public record UpdateNotesBody(@NotBlank String notes) {}

    public record AddNoteBody(@NotBlank String note) {}

    public record CandidateSearchBody(
            Map<String, Object> overrideParams,
            Integer page,
            Integer size) {}

    public record CreateIntroductionBody(
            @NotNull UUID requesterRequestId,
            @NotNull UUID candidateRequestId,
            String reason) {}
}
