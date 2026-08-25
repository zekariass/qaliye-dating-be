package com.qaliye.backend.admin;

import com.qaliye.backend.discovery.service.MatchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/matches")
public class AdminMatchReconciliationController {

    private final MatchService matchService;

    public AdminMatchReconciliationController(MatchService matchService) {
        this.matchService = matchService;
    }

    @PostMapping("/reconcile")
    public ResponseEntity<Map<String, Object>> reconcileOrphanedMutualLikes() {
        int created = matchService.reconcileOrphanedMutualLikes();
        return ResponseEntity.ok(Map.of("matches_created", created));
    }
}
