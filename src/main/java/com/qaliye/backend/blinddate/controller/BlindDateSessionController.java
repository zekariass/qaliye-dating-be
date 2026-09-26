package com.qaliye.backend.blinddate.controller;

import com.qaliye.backend.blinddate.repository.BlindDateFinalDecisionRepository.FinalDecisionRow;
import com.qaliye.backend.blinddate.repository.BlindDateParticipantRepository.ParticipantAnswerView;
import com.qaliye.backend.blinddate.repository.BlindDateParticipantRepository.ParticipantRow;
import com.qaliye.backend.blinddate.repository.BlindDateSessionRepository.MySessionRow;
import com.qaliye.backend.blinddate.repository.BlindDateSessionRepository.RoundRow;
import com.qaliye.backend.blinddate.repository.BlindDateSessionRepository.SessionQuestionRow;
import com.qaliye.backend.blinddate.repository.BlindDateSessionRepository.SessionRow;
import com.qaliye.backend.blinddate.service.BlindDateFinalDecisionService;
import com.qaliye.backend.blinddate.service.BlindDateFinalDecisionService.FinalDecisionView;
import com.qaliye.backend.blinddate.service.BlindDateParticipationService;
import com.qaliye.backend.blinddate.service.BlindDateSelectionService;
import com.qaliye.backend.blinddate.service.BlindDateSessionService;
import com.qaliye.backend.blinddate.service.BlindDateSessionService.CreatorInfo;
import com.qaliye.backend.blinddate.service.BlindDateSessionService.ParticipantReviewView;
import com.qaliye.backend.blinddate.service.BlindDateSessionService.RoundAnswersView;
import com.qaliye.backend.blinddate.service.BlindDateSessionService.SessionResultsView;
import com.qaliye.backend.blinddate.service.BlindDateSessionService.SessionView;
import com.qaliye.backend.common.CallerUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/blind-date")
public class BlindDateSessionController {

    private final BlindDateSessionService sessionService;
    private final BlindDateParticipationService participationService;
    private final BlindDateSelectionService selectionService;
    private final BlindDateFinalDecisionService finalDecisionService;

    public BlindDateSessionController(BlindDateSessionService sessionService,
                                      BlindDateParticipationService participationService,
                                      BlindDateSelectionService selectionService,
                                      BlindDateFinalDecisionService finalDecisionService) {
        this.sessionService = sessionService;
        this.participationService = participationService;
        this.selectionService = selectionService;
        this.finalDecisionService = finalDecisionService;
    }

    // ── Discovery ──────────────────────────────────────────────────────────

    @GetMapping("/sessions/discover")
    public ResponseEntity<List<Map<String, Object>>> discover(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID callerId = CallerUtils.callerId();
        var rows = participationService.discover(callerId, page, size);
        Map<UUID, CreatorInfo> creators = creatorInfos(
                rows.stream().map(d -> d.session().creatorUserId()).toList());
        Map<UUID, FinalDecisionView> decisions = finalDecisions(callerId,
                rows.stream().map(d -> d.session()).toList());
        List<Map<String, Object>> body = rows.stream()
                .map(d -> {
                    Map<String, Object> m = sessionToMap(d.session(), creators, decisions);
                    m.put("participant_count", d.participantCount());
                    return m;
                }).toList();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/sessions/mine")
    public ResponseEntity<List<Map<String, Object>>> mySessions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID callerId = CallerUtils.callerId();
        var rows = participationService.getMySessions(callerId, page, size);
        Map<UUID, CreatorInfo> creators = creatorInfos(
                rows.stream().map(r -> r.session().creatorUserId()).toList());
        Map<UUID, FinalDecisionView> decisions = finalDecisions(callerId,
                rows.stream().map(r -> r.session()).toList());
        List<Map<String, Object>> body = rows.stream()
                .map(r -> mySessionToMap(r, creators, decisions)).toList();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/participations")
    public ResponseEntity<List<Map<String, Object>>> myParticipations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID callerId = CallerUtils.callerId();
        var rows = participationService.getMyParticipations(callerId, page, size);
        Map<UUID, CreatorInfo> creators = creatorInfos(
                rows.stream().map(r -> r.session().creatorUserId()).toList());
        Map<UUID, FinalDecisionView> decisions = finalDecisions(callerId,
                rows.stream().map(r -> r.session()).toList());
        List<Map<String, Object>> body = rows.stream()
                .map(r -> mySessionToMap(r, creators, decisions)).toList();
        return ResponseEntity.ok(body);
    }

    // ── Session lifecycle ──────────────────────────────────────────────────

    @PostMapping("/sessions")
    public ResponseEntity<Map<String, Object>> createSession(
            @Valid @RequestBody CreateSessionRequest request) {
        SessionView view = sessionService.createSession(
                CallerUtils.callerId(), request.idempotencyKey(),
                request.questionIds(), request.customQuestionIds(),
                request.languageCode(), request.expiresAt());
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(sessionViewToMap(view,
                creatorInfos(List.of(view.session().creatorUserId())),
                finalDecisions(callerId, List.of(view.session()))));
    }

    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<Map<String, Object>> getSession(@PathVariable UUID sessionId) {
        UUID callerId = CallerUtils.callerId();
        SessionView view = sessionService.getSession(sessionId);
        return ResponseEntity.ok(sessionViewToMap(view,
                creatorInfos(List.of(view.session().creatorUserId())),
                finalDecisions(callerId, List.of(view.session()))));
    }

    @PostMapping("/sessions/{sessionId}/close")
    public ResponseEntity<Void> closeSession(@PathVariable UUID sessionId) {
        sessionService.closeSession(CallerUtils.callerId(), sessionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sessions/{sessionId}/rounds")
    public ResponseEntity<Map<String, Object>> createNextRound(
            @PathVariable UUID sessionId,
            @Valid @RequestBody CreateRoundRequest request) {
        RoundRow round = sessionService.createNextRound(
                CallerUtils.callerId(), sessionId, request.questionIds(), request.customQuestionIds());
        return ResponseEntity.ok(roundToMap(round));
    }

    @GetMapping("/sessions/{sessionId}/participants")
    public ResponseEntity<List<Map<String, Object>>> sessionParticipants(
            @PathVariable UUID sessionId) {
        List<Map<String, Object>> body = sessionService
                .getSessionParticipants(CallerUtils.callerId(), sessionId).stream()
                .map(this::participantReviewToMap).toList();
        return ResponseEntity.ok(body);
    }

    /**
     * Creator-facing results for a finished session: outcome, the revealed
     * finalist (identity + public profile) and their answers grouped by
     * round. The finalist's user_id is exposed here only — the reveal has
     * already happened once a finalist exists.
     */
    @GetMapping("/sessions/{sessionId}/results")
    public ResponseEntity<Map<String, Object>> sessionResults(@PathVariable UUID sessionId) {
        SessionResultsView view = sessionService.getSessionResults(CallerUtils.callerId(), sessionId);
        return ResponseEntity.ok(resultsToMap(view));
    }

    @PostMapping("/sessions/{sessionId}/rounds/close")
    public ResponseEntity<Void> closeRound(@PathVariable UUID sessionId) {
        selectionService.closeRound(CallerUtils.callerId(), sessionId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/rounds/{roundId}/questions")
    public ResponseEntity<List<Map<String, Object>>> getRoundQuestions(@PathVariable UUID roundId) {
        var view = participationService.getRoundQuestions(CallerUtils.callerId(), roundId);
        List<Map<String, Object>> body = view.questions().stream()
                .map(q -> {
                    Map<String, Object> m = sessionQuestionToMap(q);
                    m.put("my_answer", view.myAnswers().get(q.id()));
                    return m;
                }).toList();
        return ResponseEntity.ok(body);
    }

    // ── Participation ──────────────────────────────────────────────────────

    @PostMapping("/sessions/{sessionId}/join")
    public ResponseEntity<Map<String, Object>> join(@PathVariable UUID sessionId,
                                                    @Valid @RequestBody JoinRequest request) {
        var participant = participationService.join(
                CallerUtils.callerId(), sessionId, request.idempotencyKey());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("participant_id", participant.id());
        body.put("session_id", participant.sessionId());
        body.put("status", participant.status());
        body.put("current_round_id", participant.currentRoundId());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/participants/{participantId}/answers")
    public ResponseEntity<Void> submitAnswers(@PathVariable UUID participantId,
                                              @Valid @RequestBody AnswersRequest request) {
        participationService.submitAnswers(CallerUtils.callerId(), participantId, request.answers());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/participants/{participantId}/withdraw")
    public ResponseEntity<Void> withdraw(@PathVariable UUID participantId) {
        participationService.withdraw(CallerUtils.callerId(), participantId);
        return ResponseEntity.noContent().build();
    }

    // ── Selections ─────────────────────────────────────────────────────────

    @PostMapping("/sessions/{sessionId}/selections")
    public ResponseEntity<Void> select(@PathVariable UUID sessionId,
                                       @Valid @RequestBody SelectionRequest request) {
        selectionService.select(CallerUtils.callerId(), sessionId,
                request.participantId(), request.decision());
        return ResponseEntity.noContent().build();
    }

    // ── Final decision ─────────────────────────────────────────────────────

    @PostMapping("/sessions/{sessionId}/final-decision")
    public ResponseEntity<Map<String, Object>> finalDecision(
            @PathVariable UUID sessionId,
            @Valid @RequestBody FinalDecisionRequest request) {
        var fd = finalDecisionService.decide(CallerUtils.callerId(), sessionId, request.decision());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("session_id", fd.sessionId());
        body.put("creator_decision", fd.creatorDecision());
        body.put("participant_decision", fd.participantDecision());
        body.put("outcome", fd.outcome());
        body.put("match_id", fd.matchId());
        return ResponseEntity.ok(body);
    }

    // ── Requests ───────────────────────────────────────────────────────────

    public record CreateSessionRequest(@NotNull UUID idempotencyKey,
                                       List<UUID> questionIds,
                                       List<UUID> customQuestionIds,
                                       String languageCode,
                                       OffsetDateTime expiresAt) {}

    public record CreateRoundRequest(List<UUID> questionIds, List<UUID> customQuestionIds) {}

    public record JoinRequest(UUID idempotencyKey) {}

    public record AnswersRequest(@NotNull Map<UUID, String> answers) {}

    public record SelectionRequest(@NotNull UUID participantId, @NotBlank String decision) {}

    public record FinalDecisionRequest(@NotBlank String decision) {}

    // ── Mappers ────────────────────────────────────────────────────────────

    private Map<UUID, CreatorInfo> creatorInfos(List<UUID> creatorIds) {
        return sessionService.getCreatorInfos(creatorIds);
    }

    private Map<UUID, FinalDecisionView> finalDecisions(UUID callerId, List<SessionRow> sessions) {
        Map<UUID, UUID> creators = new LinkedHashMap<>();
        sessions.forEach(s -> creators.put(s.id(), s.creatorUserId()));
        return finalDecisionService.getFinalDecisions(callerId, creators.keySet(), creators);
    }

    private Map<String, Object> sessionToMap(SessionRow s, Map<UUID, CreatorInfo> creators,
                                             Map<UUID, FinalDecisionView> decisions) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("creator_user_id", s.creatorUserId());
        m.put("status", s.status());
        m.put("language_code", s.languageCode());
        m.put("expires_at", s.expiresAt());
        m.put("created_at", s.createdAt());
        m.put("creator", creatorToMap(creators.get(s.creatorUserId())));
        m.put("final_decision", finalDecisionToMap(decisions.get(s.id())));
        return m;
    }

    private Map<String, Object> finalDecisionToMap(FinalDecisionView fd) {
        if (fd == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("my_decision", fd.myDecision());
        m.put("other_party_decided", fd.otherPartyDecided());
        m.put("outcome", fd.outcome());
        m.put("match_id", fd.matchId());
        m.put("revealed_at", fd.revealedAt());
        m.put("decision_deadline_at", fd.decisionDeadlineAt());
        return m;
    }

    private Map<String, Object> creatorToMap(CreatorInfo c) {
        if (c == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gender", c.gender());
        m.put("age", c.age());
        m.put("religion", c.religion());
        m.put("relationship_intention", c.relationshipIntention());
        m.put("city", c.city());
        m.put("country", c.countryName());
        m.put("primary_photo", c.primaryPhoto() != null ? photoToMap(c.primaryPhoto()) : null);
        return m;
    }

    private Map<String, Object> photoToMap(com.qaliye.backend.discovery.dto.DiscoveryPhotoDto p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("signed_url", p.signedUrl());
        m.put("expires_at", p.expiresAt());
        return m;
    }

    private Map<String, Object> mySessionToMap(MySessionRow row, Map<UUID, CreatorInfo> creators,
                                               Map<UUID, FinalDecisionView> decisions) {
        Map<String, Object> m = sessionToMap(row.session(), creators, decisions);
        m.put("role", row.session().creatorUserId().equals(CallerUtils.callerId())
                ? "CREATOR" : "PARTICIPANT");
        m.put("participant_id", row.participantId());
        m.put("participant_status", row.participantStatus());
        m.put("current_round_id", row.currentRoundId());
        m.put("participant_count", row.participantCount());
        m.put("current_round_number", row.currentRoundNumber());
        m.put("pending_question_count", row.pendingQuestionCount());
        return m;
    }

    private Map<String, Object> participantReviewToMap(ParticipantReviewView view) {
        ParticipantRow p = view.participant();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("participant_id", p.id());
        m.put("status", p.status());
        m.put("current_round_id", p.currentRoundId());
        m.put("joined_at", p.joinedAt());
        m.put("decision", view.decision());
        m.put("answers", view.answers().stream().map(this::participantAnswerToMap).toList());
        return m;
    }

    private Map<String, Object> participantAnswerToMap(ParticipantAnswerView a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("session_question_id", a.sessionQuestionId());
        m.put("question", a.questionText());
        m.put("answer", a.answer());
        m.put("submitted_at", a.submittedAt());
        return m;
    }

    private Map<String, Object> resultsToMap(SessionResultsView view) {
        SessionRow s = view.session();
        FinalDecisionRow fd = view.decision();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("session_id", s.id());
        m.put("status", s.status());
        m.put("created_at", s.createdAt());
        m.put("participant_count", view.participantCount());
        m.put("round_count", view.roundCount());
        m.put("outcome", fd != null ? fd.outcome() : null);
        m.put("match_id", fd != null ? fd.matchId() : null);
        m.put("winner", winnerToMap(view));
        return m;
    }

    private Map<String, Object> winnerToMap(SessionResultsView view) {
        ParticipantRow finalist = view.finalist();
        if (finalist == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("participant_id", finalist.id());
        m.put("user_id", finalist.userId());
        m.put("status", finalist.status());
        m.put("profile", winnerProfileToMap(view.profile()));
        m.put("rounds", view.winnerRounds().stream().map(this::winnerRoundToMap).toList());
        return m;
    }

    private Map<String, Object> winnerProfileToMap(CreatorInfo c) {
        if (c == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("display_name", c.displayName());
        m.put("age", c.age());
        m.put("gender", c.gender());
        m.put("religion", c.religion());
        m.put("relationship_intention", c.relationshipIntention());
        m.put("city", c.city());
        m.put("country", c.countryName());
        m.put("primary_photo", c.primaryPhoto() != null ? photoToMap(c.primaryPhoto()) : null);
        return m;
    }

    private Map<String, Object> winnerRoundToMap(RoundAnswersView r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("round_id", r.round().id());
        m.put("round_number", r.round().roundNumber());
        m.put("answers", r.answers().stream().map(this::participantAnswerToMap).toList());
        return m;
    }

    private Map<String, Object> sessionViewToMap(SessionView view, Map<UUID, CreatorInfo> creators,
                                                 Map<UUID, FinalDecisionView> decisions) {
        Map<String, Object> m = sessionToMap(view.session(), creators, decisions);
        m.put("rounds", view.rounds().stream().map(this::roundToMap).toList());
        m.put("participant_count", view.participantCount());
        m.put("current_round_number", view.currentRoundNumber());
        return m;
    }

    private Map<String, Object> roundToMap(RoundRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("session_id", r.sessionId());
        m.put("round_number", r.roundNumber());
        m.put("status", r.status());
        m.put("started_at", r.startedAt());
        m.put("completed_at", r.completedAt());
        return m;
    }

    private Map<String, Object> sessionQuestionToMap(SessionQuestionRow q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", q.id());
        m.put("round_id", q.roundId());
        m.put("question", q.questionText());
        m.put("sort_order", q.sortOrder());
        return m;
    }
}
