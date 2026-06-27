package com.qaliye.backend.user;

import com.qaliye.backend.common.CallerUtils;
import com.qaliye.backend.user.dto.BlockRequest;
import com.qaliye.backend.user.dto.BlockResponse;
import com.qaliye.backend.user.dto.ReportRequest;
import com.qaliye.backend.user.dto.ReportResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class UserSocialController {

    private final UserSocialService userSocialService;

    public UserSocialController(UserSocialService userSocialService) {
        this.userSocialService = userSocialService;
    }

    @PostMapping("/{userId}/report")
    public ResponseEntity<ReportResponse> reportUser(
            @PathVariable UUID userId,
            @Valid @RequestBody ReportRequest request) {
        UUID callerId = CallerUtils.callerId();
        ReportResponse response = userSocialService.reportUser(callerId, userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{userId}/block")
    public ResponseEntity<BlockResponse> blockUser(
            @PathVariable UUID userId,
            @RequestBody(required = false) BlockRequest request) {
        UUID callerId = CallerUtils.callerId();
        BlockResponse response = userSocialService.blockUser(callerId, userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{userId}/block")
    public ResponseEntity<Void> unblockUser(@PathVariable UUID userId) {
        UUID callerId = CallerUtils.callerId();
        userSocialService.unblockUser(callerId, userId);
        return ResponseEntity.noContent().build();
    }
}
