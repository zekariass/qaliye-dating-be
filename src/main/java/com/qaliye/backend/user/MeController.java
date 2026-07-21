package com.qaliye.backend.user;

import com.qaliye.backend.common.CallerUtils;
import com.qaliye.backend.safety.BlockQueryService;
import com.qaliye.backend.user.dto.BlocksPageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class MeController {

    private static final Duration MAX_TOKEN_AGE_FOR_DELETE = Duration.ofHours(1);

    private final MeService meService;
    private final BlockQueryService blockQueryService;
    private final AccountDeletionService accountDeletionService;

    public MeController(MeService meService,
                        BlockQueryService blockQueryService,
                        AccountDeletionService accountDeletionService) {
        this.meService = meService;
        this.blockQueryService = blockQueryService;
        this.accountDeletionService = accountDeletionService;
    }

    public record DeleteAccountRequest(
            Boolean confirm
    ) {}

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

    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteMyAccount(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "confirm", required = false) Boolean confirmParam,
            @RequestBody(required = false) DeleteAccountRequest body) {

        boolean confirmed = Boolean.TRUE.equals(confirmParam)
                || (body != null && Boolean.TRUE.equals(body.confirm()));
        if (!confirmed) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "confirmation_required");
        }

        Instant issuedAt = jwt.getIssuedAt();
        if (issuedAt == null || issuedAt.isBefore(Instant.now().minus(MAX_TOKEN_AGE_FOR_DELETE))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "recent_auth_required");
        }

        UUID callerId = UUID.fromString(jwt.getSubject());
        accountDeletionService.deleteAccount(callerId);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "invalid_request", "message", ex.getMessage()));
    }
}
