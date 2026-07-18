package com.qaliye.backend.support.api;

import com.qaliye.backend.support.dto.MarkReadRequest;
import com.qaliye.backend.support.dto.SupportConversationDto;
import com.qaliye.backend.support.dto.SupportMessageDto;
import com.qaliye.backend.support.dto.SupportMessagePageDto;
import com.qaliye.backend.common.CallerUtils;
import com.qaliye.backend.support.service.SupportConversationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
@RequestMapping("/api/v1/support")
public class SupportUserController {

    private final SupportConversationService service;

    public SupportUserController(SupportConversationService service) {
        this.service = service;
    }

    @GetMapping("/conversation")
    public ResponseEntity<SupportConversationDto> getConversation() {
        return ResponseEntity.ok(service.getConversation(CallerUtils.callerId()));
    }

    @GetMapping("/conversation/messages")
    public ResponseEntity<SupportMessagePageDto> listMessages(
            @RequestParam(required = false) Long beforeSequence,
            @RequestParam(defaultValue = "25") int limit) {

        return ResponseEntity.ok(service.listMessages(CallerUtils.callerId(), beforeSequence, limit));
    }

    @PostMapping(value = "/conversation/messages",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SupportMessageDto> sendMessage(
            @RequestParam UUID clientMessageId,
            @RequestParam(required = false) String body,
            @RequestPart(name = "files", required = false) List<MultipartFile> files,
            @RequestParam(required = false) String durations) {

        SupportMessageDto dto = service.sendMessage(
                CallerUtils.callerId(), clientMessageId, body, files,
                SupportConversationService.parseDurations(durations));
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @PostMapping("/conversation/read")
    public ResponseEntity<Void> markRead(
            @Valid @RequestBody MarkReadRequest body) {

        service.markRead(CallerUtils.callerId(), body.lastReadSequence());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/conversation/close")
    public ResponseEntity<Void> close() {
        service.close(CallerUtils.callerId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/attachments/{attachmentId}/download-url")
    public ResponseEntity<Map<String, String>> getDownloadUrl(
            @PathVariable UUID attachmentId) {

        String url = service.getAttachmentDownloadUrl(CallerUtils.callerId(), attachmentId);
        return ResponseEntity.ok(Map.of("download_url", url));
    }
}
