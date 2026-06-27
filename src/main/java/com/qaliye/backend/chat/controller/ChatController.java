package com.qaliye.backend.chat.controller;

import com.qaliye.backend.chat.dto.*;
import com.qaliye.backend.chat.service.*;
import com.qaliye.backend.common.CallerUtils;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final ChatQueryService queryService;
    private final MessageCommandService messageCommandService;
    private final ReceiptService receiptService;
    private final ChatNotificationSettingsService notificationSettingsService;

    public ChatController(ChatQueryService queryService,
                          MessageCommandService messageCommandService,
                          ReceiptService receiptService,
                          ChatNotificationSettingsService notificationSettingsService) {
        this.queryService = queryService;
        this.messageCommandService = messageCommandService;
        this.receiptService = receiptService;
        this.notificationSettingsService = notificationSettingsService;
    }

    @GetMapping("/matches")
    public ResponseEntity<InboxResponse> getInbox(
            @RequestParam(defaultValue = "ALL") String filter,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "25") int limit) {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(queryService.getInbox(callerId, filter, cursor, limit));
    }

    @GetMapping("/matches/{matchId}")
    public ResponseEntity<ChatMatchMetadataDto> getMatchMetadata(@PathVariable UUID matchId) {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(queryService.getMatchMetadata(callerId, matchId));
    }

    @GetMapping("/matches/{matchId}/messages")
    public ResponseEntity<MessagesResponse> getMessages(
            @PathVariable UUID matchId,
            @RequestParam(required = false) Long beforeSequence,
            @RequestParam(required = false) Long afterSequence,
            @RequestParam(defaultValue = "50") int limit) {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(
                queryService.getMessages(callerId, matchId, beforeSequence, afterSequence, limit));
    }

    @PostMapping("/matches/{matchId}/messages")
    public ResponseEntity<ChatMessageDto> sendMessage(
            @PathVariable UUID matchId,
            @Valid @RequestBody SendMessageRequest request) {
        UUID callerId = CallerUtils.callerId();
        MessageCommandService.SendResult result =
                messageCommandService.sendMessage(callerId, matchId, request);
        int statusCode = result.isNew() ? 201 : 200;
        return ResponseEntity.status(statusCode).body(result.message());
    }

    @PostMapping("/matches/{matchId}/receipts/delivered")
    public ResponseEntity<Void> markDelivered(
            @PathVariable UUID matchId,
            @Valid @RequestBody MarkReceiptRequest request) {
        UUID callerId = CallerUtils.callerId();
        receiptService.markDelivered(callerId, matchId, request.getUpToSequence());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/matches/{matchId}/receipts/read")
    public ResponseEntity<Void> markRead(
            @PathVariable UUID matchId,
            @Valid @RequestBody MarkReceiptRequest request) {
        UUID callerId = CallerUtils.callerId();
        receiptService.markRead(callerId, matchId, request.getUpToSequence());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/matches/{matchId}/notification-settings")
    public ResponseEntity<Void> updateNotificationSettings(
            @PathVariable UUID matchId,
            @RequestBody MuteSettingsRequest request) {
        UUID callerId = CallerUtils.callerId();
        notificationSettingsService.updateMuteSetting(callerId, matchId, request.getMutedUntil());
        return ResponseEntity.noContent().build();
    }
}
