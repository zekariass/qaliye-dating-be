package com.qaliye.backend.actions;

import com.qaliye.backend.common.CallerUtils;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ActionsController {

    private final SwipeService swipeService;

    public ActionsController(SwipeService swipeService) {
        this.swipeService = swipeService;
    }

    @PostMapping({"actions/swipe", "discovery/actions"})
    public ResponseEntity<Map<String, Object>> swipe(@Valid @RequestBody SwipeRequest request) {
        UUID callerId = CallerUtils.callerId();
        SwipeService.SwipeResult result = swipeService.swipe(callerId, request);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("matched", result.matched());
        if (result.matchId() != null) {
            body.put("match_id", result.matchId());
        }
        return ResponseEntity.ok(body);
    }

    @PostMapping({"actions/rewind", "discovery/rewind"})
    public ResponseEntity<Map<String, Object>> rewind() {
        UUID callerId = CallerUtils.callerId();
        UUID rewoundUserId = swipeService.rewind(callerId);
        return ResponseEntity.ok(Map.of("rewound_user_id", rewoundUserId));
    }

    @ExceptionHandler(DailyLimitReachedException.class)
    public ResponseEntity<Map<String, Object>> handleDailyLimit(DailyLimitReachedException ex) {
        return ResponseEntity.unprocessableEntity().body(Map.of(
                "error", "daily_limit_reached",
                "limit_type", ex.getLimitType()
        ));
    }

}
