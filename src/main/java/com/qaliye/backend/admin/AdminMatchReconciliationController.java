package com.qaliye.backend.admin;

import com.qaliye.backend.common.CallerUtils;
import com.qaliye.backend.discovery.service.MatchService;
import com.qaliye.backend.user.UserStatusService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/matches")
public class AdminMatchReconciliationController {

    private final MatchService matchService;
    private final UserStatusService userStatusService;

    public AdminMatchReconciliationController(MatchService matchService,
                                              UserStatusService userStatusService) {
        this.matchService = matchService;
        this.userStatusService = userStatusService;
    }

    @PostMapping("/reconcile")
    public ResponseEntity<Map<String, Object>> reconcileOrphanedMutualLikes() {
        UUID callerId = CallerUtils.callerId();
        requireAdmin(callerId);
        int created = matchService.reconcileOrphanedMutualLikes();
        return ResponseEntity.ok(Map.of("matches_created", created));
    }

    private void requireAdmin(UUID callerId) {
        UserStatusService.UserStatus status = userStatusService.getStatus(callerId);
        if (status == null || !"ADMIN".equals(status.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin_access_required");
        }
    }
}
