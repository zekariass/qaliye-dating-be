package com.qaliye.backend.discovery.controller;

import com.qaliye.backend.discovery.dto.MatchesPageResponse;
import com.qaliye.backend.discovery.service.MatchesService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/discovery")
public class MatchesController {

    private final MatchesService matchesService;

    public MatchesController(MatchesService matchesService) {
        this.matchesService = matchesService;
    }

    @GetMapping("/matches")
    public MatchesPageResponse getMatches(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID currentUserId = requireUserId(jwt);
        return matchesService.getMatches(currentUserId, page, size);
    }

    private static UUID requireUserId(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing subject in JWT");
        }
        return UUID.fromString(subject);
    }
}
