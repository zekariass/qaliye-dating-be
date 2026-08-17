package com.qaliye.backend.discovery.controller;

import com.qaliye.backend.discovery.dto.DiscoveryProfilesResponse;
import com.qaliye.backend.discovery.dto.RevisitPassesResponse;
import com.qaliye.backend.discovery.dto.RevealResponse;
import com.qaliye.backend.discovery.dto.RewindResponse;
import com.qaliye.backend.discovery.dto.SendSuperMessageRequest;
import com.qaliye.backend.discovery.dto.SuperMessageActionResponse;
import com.qaliye.backend.discovery.dto.SuperMessageResponse;
import com.qaliye.backend.discovery.dto.SwipeActionRequest;
import com.qaliye.backend.discovery.dto.SwipeActionResponse;
import com.qaliye.backend.discovery.exception.ActorIneligibleException;
import com.qaliye.backend.discovery.exception.ActionLimitExceededException;
import com.qaliye.backend.discovery.exception.SelfActionException;
import com.qaliye.backend.discovery.service.DiscoveryFeedService;
import com.qaliye.backend.discovery.service.DiscoveryQueryService;
import com.qaliye.backend.discovery.service.RevisitPassesService;
import com.qaliye.backend.discovery.service.RevealService;
import com.qaliye.backend.discovery.service.RewindService;
import com.qaliye.backend.discovery.service.SuperMessageService;
import com.qaliye.backend.discovery.service.SwipeActionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/discovery")
public class DiscoveryProfilesController {

    private final DiscoveryFeedService feedService;
    private final SwipeActionService swipeService;
    private final RewindService rewindService;
    private final RevealService revealService;
    private final DiscoveryQueryService queryService;
    private final RevisitPassesService revisitPassesService;
    private final SuperMessageService superMessageService;

    public DiscoveryProfilesController(DiscoveryFeedService feedService,
                                        SwipeActionService swipeService,
                                        RewindService rewindService,
                                        RevealService revealService,
                                        DiscoveryQueryService queryService,
                                        RevisitPassesService revisitPassesService,
                                        SuperMessageService superMessageService) {
        this.feedService = feedService;
        this.swipeService = swipeService;
        this.rewindService = rewindService;
        this.revealService = revealService;
        this.queryService = queryService;
        this.revisitPassesService = revisitPassesService;
        this.superMessageService = superMessageService;
    }

    @GetMapping("/profiles")
    public DiscoveryProfilesResponse getProfiles(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String cursor) {
        UUID actorId = requireActorId(jwt);
        return feedService.fetchProfiles(actorId, cursor);
    }

    @PostMapping("/actions/like")
    public SwipeActionResponse like(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody SwipeActionRequest req) {
        UUID actorId = requireActorId(jwt);
        checkSelfAction(actorId, req.targetUserId());
        checkActorEligibility(actorId);
        return swipeService.recordLike(actorId, req.targetUserId(), req.clientActionId());
    }

    @PostMapping("/actions/pass")
    public SwipeActionResponse pass(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody SwipeActionRequest req) {
        UUID actorId = requireActorId(jwt);
        checkSelfAction(actorId, req.targetUserId());
        checkActorEligibility(actorId);
        return swipeService.recordPass(actorId, req.targetUserId(), req.clientActionId());
    }

    @PostMapping("/actions/superlike")
    public SwipeActionResponse superLike(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody SwipeActionRequest req) {
        UUID actorId = requireActorId(jwt);
        checkSelfAction(actorId, req.targetUserId());
        checkActorEligibility(actorId);
        return swipeService.recordSuperLike(actorId, req.targetUserId(), req.clientActionId());
    }

    @PostMapping("/actions/rewind")
    public RewindResponse rewind(@AuthenticationPrincipal Jwt jwt) {
        UUID actorId = requireActorId(jwt);
        checkActorEligibility(actorId);
        return rewindService.rewind(actorId);
    }

    @PostMapping("/actions/{actionId}/reveal")
    public RevealResponse revealLike(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID actionId) {
        UUID actorId = requireActorId(jwt);
        return revealService.reveal(actorId, actionId);
    }

    @PostMapping("/passes/revisit")
    public RevisitPassesResponse revisitPasses(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "10") int count) {
        if (count != 10 && count != 20 && count != 30) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "count must be 10, 20, or 30");
        }
        UUID actorId = requireActorId(jwt);
        checkActorEligibility(actorId);
        return revisitPassesService.revisitPasses(actorId, count);
    }

    // ── Super Messages ──────────────────────────────────────────────────────

    @PostMapping("/super-messages")
    public SuperMessageResponse sendSuperMessage(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody SendSuperMessageRequest req) {
        UUID actorId = requireActorId(jwt);
        checkSelfAction(actorId, req.targetUserId());
        checkActorEligibility(actorId);
        return superMessageService.send(actorId, req.targetUserId(), req.message(), req.idempotencyKey());
    }

    @GetMapping("/super-messages/{messageId}")
    public SuperMessageResponse getSuperMessage(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID messageId) {
        UUID actorId = requireActorId(jwt);
        return superMessageService.findById(actorId, messageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "super_message_not_found"));
    }

    @GetMapping("/super-messages")
    public List<SuperMessageResponse> listSuperMessages(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "sent") String direction,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        UUID actorId = requireActorId(jwt);
        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be 1-100");
        }
        if (offset < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "offset must be >= 0");
        }
        if ("received".equalsIgnoreCase(direction)) {
            return superMessageService.listReceived(actorId, limit, offset);
        }
        return superMessageService.listSent(actorId, limit, offset);
    }

    @PostMapping("/super-messages/{messageId}/accept")
    public SuperMessageActionResponse acceptSuperMessage(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID messageId) {
        UUID actorId = requireActorId(jwt);
        checkActorEligibility(actorId);
        return superMessageService.accept(actorId, messageId);
    }

    @PostMapping("/super-messages/{messageId}/pass")
    public SuperMessageActionResponse passSuperMessage(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID messageId) {
        UUID actorId = requireActorId(jwt);
        checkActorEligibility(actorId);
        return superMessageService.pass(actorId, messageId);
    }

    private void checkSelfAction(UUID actorId, UUID targetId) {
        if (actorId.equals(targetId)) {
            throw new SelfActionException();
        }
    }

    private void checkActorEligibility(UUID actorId) {
        DiscoveryQueryService.ActorEligibilityResult result =
                queryService.checkActorEligibilityReason(actorId);
        if (result == DiscoveryQueryService.ActorEligibilityResult.ACCOUNT_INELIGIBLE) {
            throw ActorIneligibleException.accountIneligible();
        }
        if (result == DiscoveryQueryService.ActorEligibilityResult.PROFILE_INCOMPLETE) {
            throw ActorIneligibleException.profileIncomplete();
        }
    }

    @ExceptionHandler(ActionLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleActionLimit(ActionLimitExceededException ex) {
        return ResponseEntity.status(ex.getHttpStatus()).body(Map.of(
                "error", Map.of(
                        "code", ex.getErrorCode(),
                        "message", ex.getMessage(),
                        "details", Map.of(
                                "action_type", ex.getActionType(),
                                "period_type", ex.getPeriodType()
                        )
                )
        ));
    }

    private static UUID requireActorId(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing subject in JWT");
        }
        return UUID.fromString(subject);
    }
}
