package com.qaliye.backend.user;

import com.qaliye.backend.common.CallerUtils;
import com.qaliye.backend.safety.BlockQueryService;
import com.qaliye.backend.user.dto.BlocksPageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class MeController {

    private final MeService meService;
    private final BlockQueryService blockQueryService;

    public MeController(MeService meService, BlockQueryService blockQueryService) {
        this.meService = meService;
        this.blockQueryService = blockQueryService;
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me() {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(meService.getMe(callerId));
    }

    @GetMapping("/me/blocks")
    public ResponseEntity<BlocksPageResponse> myBlocks(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(blockQueryService.listActiveBlocks(callerId, cursor, limit));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "invalid_request", "message", ex.getMessage()));
    }
}
