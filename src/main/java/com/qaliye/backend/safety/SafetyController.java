package com.qaliye.backend.safety;

import com.qaliye.backend.common.CallerUtils;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class SafetyController {

    private final BlockService blockService;

    public SafetyController(BlockService blockService) {
        this.blockService = blockService;
    }

    @PostMapping("/safety/block")
    public ResponseEntity<Void> blockLegacy(@Valid @RequestBody BlockRequest request) {
        UUID callerId = CallerUtils.callerId();
        blockService.block(callerId, request.getBlockedUserId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{targetUserId}/block")
    public ResponseEntity<Void> block(@PathVariable UUID targetUserId) {
        UUID callerId = CallerUtils.callerId();
        blockService.block(callerId, targetUserId);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(SafetyException.class)
    public ResponseEntity<Map<String, Object>> handleSafetyException(SafetyException ex) {
        return ResponseEntity.status(ex.getStatus()).body(Map.of("error", ex.getError()));
    }
}
