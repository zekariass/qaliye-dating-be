package com.qaliye.backend.admin;

import com.qaliye.backend.common.CallerUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminAccountDeletionController {

    private final AdminAccountDeletionService deletionService;

    public AdminAccountDeletionController(AdminAccountDeletionService deletionService) {
        this.deletionService = deletionService;
    }

    public record AdminDeleteAccountRequest(String reason) {}

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> deleteAccount(
            @PathVariable UUID userId,
            @RequestBody(required = false) AdminDeleteAccountRequest request) {
        UUID callerId = CallerUtils.callerId();
        String reason = request != null ? request.reason() : null;
        deletionService.deleteAccount(callerId, userId, reason);
        return ResponseEntity.noContent().build();
    }
}
