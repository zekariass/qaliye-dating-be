package com.qaliye.backend.discovery.controller;

import com.qaliye.backend.discovery.dto.DiscoveryProfilesResponse;
import com.qaliye.backend.discovery.dto.RewindResponse;
import com.qaliye.backend.discovery.dto.SwipeActionRequest;
import com.qaliye.backend.discovery.dto.SwipeActionResponse;
import com.qaliye.backend.discovery.exception.ActorIneligibleException;
import com.qaliye.backend.discovery.exception.SelfActionException;
import com.qaliye.backend.discovery.service.DiscoveryFeedService;
import com.qaliye.backend.discovery.service.DiscoveryQueryService;
import com.qaliye.backend.discovery.service.RewindService;
import com.qaliye.backend.discovery.service.SwipeActionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/discovery")
public class DiscoveryProfilesController {

    private final DiscoveryFeedService feedService;
    private final SwipeActionService swipeService;
    private final RewindService rewindService;
    private final DiscoveryQueryService queryService;

    public DiscoveryProfilesController(DiscoveryFeedService feedService,
                                        SwipeActionService swipeService,
                                        RewindService rewindService,
                                        DiscoveryQueryService queryService) {
        this.feedService = feedService;
        this.swipeService = swipeService;
        this.rewindService = rewindService;
        this.queryService = queryService;
    }

    @GetMapping("/profiles")
    public DiscoveryProfilesResponse getProfiles(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "NEARBY") String locationFilter,
            @RequestParam(required = false) String cursor) {
        UUID actorId = requireActorId(jwt);
        return feedService.fetchProfiles(actorId, locationFilter, cursor);
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

    private static UUID requireActorId(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing subject in JWT");
        }
        return UUID.fromString(subject);
    }
}
