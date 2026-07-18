package com.qaliye.backend.support.api;

import com.qaliye.backend.support.dto.AddNoteRequest;
import com.qaliye.backend.support.dto.AssignmentRequest;
import com.qaliye.backend.support.dto.MarkReadRequest;
import com.qaliye.backend.support.dto.PriorityRequest;
import com.qaliye.backend.support.dto.StaffConversationDetailDto;
import com.qaliye.backend.support.dto.StaffConversationSummaryDto;
import com.qaliye.backend.support.dto.SupportInternalNoteDto;
import com.qaliye.backend.support.dto.SupportMessageDto;
import com.qaliye.backend.support.dto.SupportMessagePageDto;
import com.qaliye.backend.common.CallerUtils;
import com.qaliye.backend.support.service.SupportConversationService;
import com.qaliye.backend.support.service.SupportStaffService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/staff/support")
public class SupportStaffController {

    private final SupportStaffService service;

    public SupportStaffController(SupportStaffService service) {
        this.service = service;
    }

    @GetMapping("/conversations")
    public ResponseEntity<List<StaffConversationSummaryDto>> listConversations(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID assignedToStaffId,
            @RequestParam(required = false) Boolean unassignedOnly,
            @RequestParam(required = false) Integer priority,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(defaultValue = "0") int offset) {

        return ResponseEntity.ok(service.listConversations(
                CallerUtils.callerId(), status, assignedToStaffId, unassignedOnly, priority, limit, offset));
    }

    @GetMapping("/conversations/{conversationId}")
    public ResponseEntity<StaffConversationDetailDto> getConversationDetail(
            @PathVariable UUID conversationId) {

        return ResponseEntity.ok(service.getConversationDetail(CallerUtils.callerId(), conversationId));
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<SupportMessagePageDto> listMessages(
            @PathVariable UUID conversationId,
            @RequestParam(required = false) Long beforeSequence,
            @RequestParam(defaultValue = "50") int limit) {

        return ResponseEntity.ok(service.listMessages(CallerUtils.callerId(), conversationId, beforeSequence, limit));
    }

    @PostMapping(value = "/conversations/{conversationId}/messages",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SupportMessageDto> sendMessage(
            @PathVariable UUID conversationId,
            @RequestParam UUID clientMessageId,
            @RequestParam(required = false) String body,
            @RequestPart(name = "files", required = false) List<MultipartFile> files,
            @RequestParam(required = false) String durations) {

        SupportMessageDto dto = service.sendMessage(
                CallerUtils.callerId(), conversationId, clientMessageId, body, files,
                SupportConversationService.parseDurations(durations));
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @GetMapping("/conversations/{conversationId}/notes")
    public ResponseEntity<List<SupportInternalNoteDto>> listNotes(
            @PathVariable UUID conversationId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {

        return ResponseEntity.ok(service.listNotes(CallerUtils.callerId(), conversationId, limit, offset));
    }

    @PostMapping("/conversations/{conversationId}/notes")
    public ResponseEntity<SupportInternalNoteDto> addNote(
            @PathVariable UUID conversationId,
            @Valid @RequestBody AddNoteRequest body) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.addNote(CallerUtils.callerId(), conversationId, body.clientNoteId(), body.body()));
    }

    @PostMapping("/conversations/{conversationId}/read")
    public ResponseEntity<Void> markRead(
            @PathVariable UUID conversationId,
            @Valid @RequestBody MarkReadRequest body) {

        service.markRead(CallerUtils.callerId(), conversationId, body.lastReadSequence());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/conversations/{conversationId}/assignment")
    public ResponseEntity<Void> assign(
            @PathVariable UUID conversationId,
            @RequestBody AssignmentRequest body) {

        service.assign(CallerUtils.callerId(), conversationId, body.assignedStaffUserId());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/conversations/{conversationId}/priority")
    public ResponseEntity<Void> setPriority(
            @PathVariable UUID conversationId,
            @Valid @RequestBody PriorityRequest body) {

        service.setPriority(CallerUtils.callerId(), conversationId, body.priority());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/conversations/{conversationId}/close")
    public ResponseEntity<Void> close(
            @PathVariable UUID conversationId) {

        service.close(CallerUtils.callerId(), conversationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/conversations/{conversationId}/reopen")
    public ResponseEntity<Void> reopen(
            @PathVariable UUID conversationId) {

        service.reopen(CallerUtils.callerId(), conversationId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/conversations/{conversationId}/attachments/{attachmentId}/download-url")
    public ResponseEntity<Map<String, String>> getDownloadUrl(
            @PathVariable UUID conversationId,
            @PathVariable UUID attachmentId) {

        String url = service.getAttachmentDownloadUrl(CallerUtils.callerId(), attachmentId);
        return ResponseEntity.ok(Map.of("download_url", url));
    }
}
