package com.qaliye.backend.messaging;

import com.qaliye.backend.common.CallerUtils;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class MessagingController {

    private final MessagingService messagingService;

    public MessagingController(MessagingService messagingService) {
        this.messagingService = messagingService;
    }

    @PostMapping("/messages")
    public ResponseEntity<MessageDto> sendMessage(@Valid @RequestBody SendMessageRequest request) {
        UUID callerId = CallerUtils.callerId();
        MessagingService.MessageResult result = messagingService.sendMessage(callerId, request);
        int statusCode = result.isNew() ? 201 : 200;
        return ResponseEntity.status(statusCode).body(result.dto());
    }

    @PatchMapping("/matches/{matchId}/read")
    public ResponseEntity<Void> markRead(@PathVariable UUID matchId) {
        UUID callerId = CallerUtils.callerId();
        messagingService.markRead(callerId, matchId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/matches/{matchId}")
    public ResponseEntity<Void> unmatch(@PathVariable UUID matchId) {
        UUID callerId = CallerUtils.callerId();
        boolean didUnmatch = messagingService.unmatch(callerId, matchId);
        return didUnmatch
                ? ResponseEntity.noContent().build()
                : ResponseEntity.ok().build();
    }

    @ExceptionHandler(MessagingException.class)
    public ResponseEntity<Map<String, Object>> handleMessagingException(MessagingException ex) {
        return ResponseEntity.status(ex.getStatus()).body(Map.of("error", ex.getError()));
    }
}
