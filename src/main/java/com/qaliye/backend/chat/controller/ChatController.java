package com.qaliye.backend.chat.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaliye.backend.chat.dto.*;
import com.qaliye.backend.chat.exception.InvalidMessageException;
import com.qaliye.backend.chat.service.*;
import com.qaliye.backend.common.CallerUtils;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final ChatQueryService queryService;
    private final MessageCommandService messageCommandService;
    private final ReceiptService receiptService;
    private final ChatNotificationSettingsService notificationSettingsService;
    private final ObjectMapper objectMapper;

    public ChatController(ChatQueryService queryService,
                          MessageCommandService messageCommandService,
                          ReceiptService receiptService,
                          ChatNotificationSettingsService notificationSettingsService,
                          ObjectMapper objectMapper) {
        this.queryService = queryService;
        this.messageCommandService = messageCommandService;
        this.receiptService = receiptService;
        this.notificationSettingsService = notificationSettingsService;
        this.objectMapper = objectMapper;
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

    @DeleteMapping("/matches/{matchId}/messages")
    public ResponseEntity<Void> clearConversation(@PathVariable UUID matchId) {
        UUID callerId = CallerUtils.callerId();
        messageCommandService.clearConversation(callerId, matchId);
        return ResponseEntity.noContent().build();
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

    @PostMapping(value = "/matches/{matchId}/messages/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ChatMessageDto> sendMessageWithAttachments(
            @PathVariable UUID matchId,
            @RequestPart("request") String requestJson,
            @RequestPart(value = "files", required = false) List<MultipartFile> files,
            @RequestParam(value = "durations", required = false) List<Long> durationsParam) {
        UUID callerId = CallerUtils.callerId();
        SendMessageRequest request;
        try {
            request = objectMapper.readValue(requestJson, SendMessageRequest.class);
        } catch (Exception e) {
            throw new InvalidMessageException("Invalid request format: " + e.getMessage());
        }
        if (request.getClientMessageId() == null) {
            throw new InvalidMessageException("clientMessageId must not be null");
        }
        if (request.getMessageType() == null) {
            throw new InvalidMessageException("messageType must not be null");
        }
        List<Long> durations = (request.getDurations() != null && !request.getDurations().isEmpty())
                ? request.getDurations() : durationsParam;
        MessageCommandService.SendResult result =
                messageCommandService.sendMessageWithAttachments(callerId, matchId, request, files, durations);
        int statusCode = result.isNew() ? 201 : 200;
        return ResponseEntity.status(statusCode).body(result.message());
    }

    @PostMapping("/attachments/{attachmentId}/signed-url")
    public ResponseEntity<ChatAttachmentDto> refreshSignedUrl(
            @PathVariable UUID attachmentId) {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(queryService.refreshAttachmentSignedUrl(callerId, attachmentId));
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
