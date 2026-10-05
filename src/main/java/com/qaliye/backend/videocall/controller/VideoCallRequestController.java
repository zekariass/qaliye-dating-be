package com.qaliye.backend.videocall.controller;

import com.qaliye.backend.common.CallerUtils;
import com.qaliye.backend.videocall.dto.VideoCallRequestView;
import com.qaliye.backend.videocall.provider.VideoCallProvider;
import com.qaliye.backend.videocall.service.VideoCallRequestService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * User-facing video-call-request endpoints — post-match calls for ANY match
 * source. All endpoints resolve the caller from the JWT.
 *
 * <p>Base paths: {@code /api/v1/video-call-requests} and
 * {@code /api/v1/matches/{matchId}/video-call-requests}.
 */
@RestController
@RequestMapping("/api/v1")
public class VideoCallRequestController {

    private final VideoCallRequestService service;

    public VideoCallRequestController(VideoCallRequestService service) {
        this.service = service;
    }

    /** Create (or idempotently return) the live request for a match. */
    @PostMapping("/video-call-requests")
    public ResponseEntity<VideoCallRequestView> createRequest(
            @RequestBody @Valid CreateRequestBody body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createRequest(CallerUtils.callerId(),
                        body.matchId(), body.callType()));
    }

    /** Request history + live request for a match. */
    @GetMapping("/matches/{matchId}/video-call-requests")
    public ResponseEntity<Map<String, Object>> listForMatch(
            @PathVariable UUID matchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(
                service.listForMatch(matchId, CallerUtils.callerId(), page, size));
    }

    @PostMapping("/video-call-requests/{id}/accept")
    public ResponseEntity<VideoCallRequestView> accept(@PathVariable UUID id) {
        return ResponseEntity.ok(service.accept(id, CallerUtils.callerId()));
    }

    @PostMapping("/video-call-requests/{id}/decline")
    public ResponseEntity<VideoCallRequestView> decline(@PathVariable UUID id) {
        return ResponseEntity.ok(service.decline(id, CallerUtils.callerId()));
    }

    @PostMapping("/video-call-requests/{id}/cancel")
    public ResponseEntity<VideoCallRequestView> cancel(@PathVariable UUID id) {
        return ResponseEntity.ok(service.cancel(id, CallerUtils.callerId()));
    }

    /** Requester re-notifies the responder while PENDING (cooldown-guarded). */
    @PostMapping("/video-call-requests/{id}/remind")
    public ResponseEntity<VideoCallRequestView> remind(@PathVariable UUID id) {
        return ResponseEntity.ok(service.remind(id, CallerUtils.callerId()));
    }

    /**
     * Mint join credentials (channel + token + uid + expiry). First requester
     * join charges the VIDEO_CALL action once.
     */
    @PostMapping("/video-call-requests/{id}/join")
    public ResponseEntity<VideoCallProvider.JoinSession> join(@PathVariable UUID id) {
        return ResponseEntity.ok(service.join(id, CallerUtils.callerId()));
    }

    @PostMapping("/video-call-requests/{id}/end")
    public ResponseEntity<VideoCallRequestView> end(@PathVariable UUID id) {
        return ResponseEntity.ok(service.end(id, CallerUtils.callerId()));
    }

    /** {@code callType}: "VIDEO" (default) or "AUDIO". */
    public record CreateRequestBody(@NotNull UUID matchId, String callType) {}
}
