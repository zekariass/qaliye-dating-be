package com.qaliye.backend.user;

import com.qaliye.backend.common.CallerUtils;
import com.qaliye.backend.user.dto.AdminUserDetailDto;
import com.qaliye.backend.user.dto.UpdateUserRoleRequest;
import com.qaliye.backend.user.dto.UpdateUserStatusRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listUsers(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(adminUserService.listUsers(status, role, search, page, pageSize));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<AdminUserDetailDto> getUserDetail(@PathVariable UUID userId) {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(adminUserService.getUserDetail(userId));
    }

    @PatchMapping("/{userId}/status")
    public ResponseEntity<Void> updateUserStatus(
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateUserStatusRequest request) {
        UUID callerId = CallerUtils.callerId();
        adminUserService.updateUserStatus(callerId, userId, request.getStatus(), request.getReason());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{userId}/role")
    public ResponseEntity<Void> updateUserRole(
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateUserRoleRequest request) {
        UUID callerId = CallerUtils.callerId();
        adminUserService.updateUserRole(callerId, userId, request.getRole());
        return ResponseEntity.noContent().build();
    }
}
