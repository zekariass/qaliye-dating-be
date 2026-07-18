package com.qaliye.backend.support.service;

import com.qaliye.backend.storage.SupabaseStorageService;
import com.qaliye.backend.support.SupportProperties;
import com.qaliye.backend.support.dto.*;
import com.qaliye.backend.support.exception.SupportRpcException;
import com.qaliye.backend.support.repository.SupportConversationRepository;
import com.qaliye.backend.support.repository.SupportConversationRepository.ConversationRow;
import com.qaliye.backend.support.repository.SupportMessageRepository;
import com.qaliye.backend.support.repository.SupportMessageRepository.AttachmentRow;
import com.qaliye.backend.support.repository.SupportMessageRepository.MessageRow;
import com.qaliye.backend.support.repository.SupportNoteRepository;
import com.qaliye.backend.support.repository.SupportNoteRepository.NoteRow;
import com.qaliye.backend.user.UserStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SupportStaffService {

    private static final Logger log = LoggerFactory.getLogger(SupportStaffService.class);

    private final SupportConversationRepository convRepo;
    private final SupportMessageRepository msgRepo;
    private final SupportNoteRepository noteRepo;
    private final SupabaseStorageService storageService;
    private final SupportProperties props;
    private final UserStatusService userStatusService;
    private final SupportConversationService conversationService;

    public SupportStaffService(
            SupportConversationRepository convRepo,
            SupportMessageRepository msgRepo,
            SupportNoteRepository noteRepo,
            SupabaseStorageService storageService,
            SupportProperties props,
            UserStatusService userStatusService,
            SupportConversationService conversationService) {
        this.convRepo = convRepo;
        this.msgRepo = msgRepo;
        this.noteRepo = noteRepo;
        this.storageService = storageService;
        this.props = props;
        this.userStatusService = userStatusService;
        this.conversationService = conversationService;
    }

    public void requireStaffRole(UUID userId) {
        UserStatusService.UserStatus status = userStatusService.getStatus(userId);
        if (status == null
                || (!"ADMIN".equals(status.role()) && !"MODERATOR".equals(status.role()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "ADMIN or MODERATOR role required for support staff operations");
        }
    }

    public List<StaffConversationSummaryDto> listConversations(
            UUID staffUserId,
            String status,
            UUID assignedToStaffId,
            Boolean unassignedOnly,
            Integer priority,
            int limit,
            int offset) {

        requireStaffRole(staffUserId);
        int capped = Math.min(Math.max(limit, 1), 100);
        List<ConversationRow> rows = convRepo.listForQueue(
                status, assignedToStaffId, unassignedOnly, priority, capped, offset);
        return rows.stream().map(this::toSummaryDto).toList();
    }

    public StaffConversationDetailDto getConversationDetail(UUID staffUserId, UUID conversationId) {
        requireStaffRole(staffUserId);
        ConversationRow row = convRepo.findById(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Support conversation not found"));
        long mySeq = convRepo.findMyReadSequence(conversationId, staffUserId);
        return toDetailDto(row, mySeq);
    }

    public SupportMessagePageDto listMessages(
            UUID staffUserId,
            UUID conversationId,
            Long beforeSequence,
            int limit) {

        requireStaffRole(staffUserId);
        convRepo.findById(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Support conversation not found"));

        int capped = Math.min(Math.max(limit, 1), 100);
        List<MessageRow> messages = msgRepo.findMessages(conversationId, beforeSequence, capped);

        List<UUID> msgIds = messages.stream().map(MessageRow::id).toList();
        Map<UUID, List<AttachmentRow>> byMsg = groupAttachments(msgIds);

        List<SupportMessageDto> dtos = messages.stream()
                .map(m -> conversationService.toMessageDto(m, byMsg.getOrDefault(m.id(), List.of())))
                .toList();

        Long nextCursor = messages.size() == capped
                ? messages.get(messages.size() - 1).sequenceNumber()
                : null;

        return new SupportMessagePageDto(dtos, nextCursor);
    }

    public SupportMessageDto sendMessage(
            UUID staffUserId,
            UUID conversationId,
            UUID clientMessageId,
            String body,
            List<MultipartFile> files,
            List<Long> durations) {

        requireStaffRole(staffUserId);
        convRepo.findById(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Support conversation not found"));

        List<MultipartFile> safeFiles = files != null ? files : List.of();

        if ((body == null || body.isBlank()) && safeFiles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A support message must contain a body or at least one attachment");
        }
        if (safeFiles.size() > props.getMaxFilesPerMessage()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A message may contain at most " + props.getMaxFilesPerMessage() + " attachments");
        }
        validateFiles(safeFiles, durations);

        List<UploadedAttachment> uploaded = new ArrayList<>();
        try {
            for (int i = 0; i < safeFiles.size(); i++) {
                MultipartFile file = safeFiles.get(i);
                String contentType = SupportConversationService.normalizeAudioContentType(file.getContentType(), file.getOriginalFilename());
                String sanitizedName = SupportConversationService.sanitizeFileName(
                        file.getOriginalFilename());
                String storagePath = "support/" + conversationId + "/" + clientMessageId
                        + "/" + i + "-" + UUID.randomUUID() + "-" + sanitizedName;
                byte[] bytes;
                try {
                    bytes = file.getBytes();
                } catch (IOException e) {
                    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                            "Failed to read uploaded file");
                }
                storageService.uploadFile(props.getAttachmentsBucket(), storagePath, bytes,
                        contentType);
                String kind = SupportConversationService.detectAttachmentKind(contentType);
                Long durationMs = (durations != null && i < durations.size()) ? durations.get(i) : null;
                if ("VOICE".equals(kind) && durationMs == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Voice attachment requires duration_ms");
                }
                if (!"VOICE".equals(kind)) {
                    durationMs = null;
                }
                uploaded.add(new UploadedAttachment(
                        props.getAttachmentsBucket(), storagePath,
                        sanitizedName, contentType, file.getSize(),
                        kind, durationMs));
            }

            String attachmentsJson = buildAttachmentsJson(uploaded);
            MessageRow msg;
            try {
                msg = msgRepo.callAppendStaffMessage(
                        conversationId, staffUserId, clientMessageId,
                        body != null ? body.strip() : null,
                        attachmentsJson, "{}");
            } catch (Exception e) {
                throw SupportRpcException.translate(e);
            }

            List<AttachmentRow> attachments = msgRepo.findAttachmentsByMessageId(msg.id());
            log.info("action=send_support_staff_message staffId={} conversationId={} messageId={} attachments={}",
                    staffUserId, conversationId, msg.id(), attachments.size());
            return conversationService.toMessageDto(msg, attachments);

        } catch (Exception ex) {
            cleanupUploads(uploaded);
            throw ex;
        }
    }

    public List<SupportInternalNoteDto> listNotes(
            UUID staffUserId,
            UUID conversationId,
            int limit,
            int offset) {

        requireStaffRole(staffUserId);
        convRepo.findById(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Support conversation not found"));
        return noteRepo.findNotes(conversationId, limit, offset).stream()
                .map(this::toNoteDto)
                .toList();
    }

    public SupportInternalNoteDto addNote(
            UUID staffUserId,
            UUID conversationId,
            UUID clientNoteId,
            String body) {

        requireStaffRole(staffUserId);
        NoteRow note;
        try {
            note = noteRepo.callAppendNote(conversationId, staffUserId, clientNoteId, body, "{}");
        } catch (Exception e) {
            throw SupportRpcException.translate(e);
        }
        log.info("action=add_support_internal_note staffId={} conversationId={} noteId={}",
                staffUserId, conversationId, note.id());
        return toNoteDto(note);
    }

    public void markRead(UUID staffUserId, UUID conversationId, long lastReadSequence) {
        requireStaffRole(staffUserId);
        try {
            convRepo.callMarkReadByStaff(conversationId, staffUserId, lastReadSequence);
        } catch (Exception e) {
            throw SupportRpcException.translate(e);
        }
    }

    public void assign(UUID actorStaffUserId, UUID conversationId, UUID assignedStaffUserId) {
        requireStaffRole(actorStaffUserId);
        try {
            convRepo.callAssign(conversationId, actorStaffUserId, assignedStaffUserId);
        } catch (Exception e) {
            throw SupportRpcException.translate(e);
        }
        log.info("action=assign_support_conversation actor={} conversationId={} assignedTo={}",
                actorStaffUserId, conversationId, assignedStaffUserId);
    }

    public void setPriority(UUID staffUserId, UUID conversationId, int priority) {
        requireStaffRole(staffUserId);
        try {
            convRepo.callSetPriority(conversationId, staffUserId, priority);
        } catch (Exception e) {
            throw SupportRpcException.translate(e);
        }
    }

    public void close(UUID staffUserId, UUID conversationId) {
        requireStaffRole(staffUserId);
        try {
            convRepo.callCloseByStaff(conversationId, staffUserId);
        } catch (Exception e) {
            throw SupportRpcException.translate(e);
        }
        log.info("action=close_support_conversation_by_staff staffId={} conversationId={}",
                staffUserId, conversationId);
    }

    public void reopen(UUID staffUserId, UUID conversationId) {
        requireStaffRole(staffUserId);
        try {
            convRepo.callReopen(conversationId, staffUserId);
        } catch (Exception e) {
            throw SupportRpcException.translate(e);
        }
        log.info("action=reopen_support_conversation staffId={} conversationId={}",
                staffUserId, conversationId);
    }

    public String getAttachmentDownloadUrl(UUID staffUserId, UUID attachmentId) {
        requireStaffRole(staffUserId);
        AttachmentRow att = msgRepo.findAttachmentWithConversationUserId(attachmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Attachment not found"));
        String url = storageService.generateSignedUrl(
                att.storageBucket(), att.storagePath(), props.getAttachmentSignedUrlTtlSeconds());
        if (url == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to generate download URL");
        }
        return url;
    }

    private void validateFiles(List<MultipartFile> files, List<Long> durations) {
        Set<String> allowed = Set.copyOf(props.getAllowedContentTypes());
        Set<String> voiceAllowed = Set.copyOf(props.getVoiceAllowedContentTypes());
        long voiceMaxSize = props.getVoiceMaxFileSizeBytes();
        int voiceMaxDurationSec = props.getVoiceMaxDurationSeconds();
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            String ct = file.getContentType();
            String normalizedCt = SupportConversationService.normalizeAudioContentType(ct, file.getOriginalFilename());
            boolean isVoice = voiceAllowed.contains(normalizedCt);
            boolean isRegular = allowed.contains(ct);

            if (!isVoice && !isRegular) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Content type not allowed: " + ct);
            }

            long maxSize = isVoice ? Math.min(voiceMaxSize, props.getMaxFileSizeBytes()) : props.getMaxFileSizeBytes();
            if (file.getSize() > maxSize) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "File exceeds maximum size: " + file.getOriginalFilename());
            }

            if (isVoice) {
                String ext = fileExtension(file.getOriginalFilename());
                if (!VALID_AUDIO_EXTS.contains(ext)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Audio file extension not allowed: " + ext);
                }
                Long durationMs = (durations != null && i < durations.size()) ? durations.get(i) : null;
                if (durationMs == null || durationMs <= 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Voice attachment requires a positive duration_ms");
                }
                if (durationMs > voiceMaxDurationSec * 1000L) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Voice recording exceeds maximum duration of " + voiceMaxDurationSec + " seconds");
                }
            }
        }
    }

    private void cleanupUploads(List<UploadedAttachment> uploaded) {
        for (UploadedAttachment att : uploaded) {
            try {
                storageService.deleteObject(att.bucket(), att.path());
            } catch (Exception ex) {
                log.warn("Failed to clean up orphaned support attachment bucket={} path={}",
                        att.bucket(), att.path());
            }
        }
    }

    private String buildAttachmentsJson(List<UploadedAttachment> uploaded) {
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ArrayNode array = om.createArrayNode();
        for (UploadedAttachment att : uploaded) {
            com.fasterxml.jackson.databind.node.ObjectNode obj = array.addObject();
            obj.put("storage_bucket", att.bucket())
                    .put("storage_path", att.path())
                    .put("file_name", att.fileName())
                    .put("content_type", att.contentType())
                    .put("file_size_bytes", att.sizeBytes())
                    .put("attachment_kind", att.attachmentKind() != null ? att.attachmentKind() : "OTHER");
            if (att.durationMs() != null) {
                obj.put("duration_ms", att.durationMs());
            }
        }
        return array.toString();
    }

    private Map<UUID, List<AttachmentRow>> groupAttachments(List<UUID> messageIds) {
        if (messageIds.isEmpty()) return Map.of();
        return msgRepo.findAttachmentsByMessageIds(messageIds).stream()
                .collect(Collectors.groupingBy(AttachmentRow::messageId));
    }

    private StaffConversationSummaryDto toSummaryDto(ConversationRow row) {
        return new StaffConversationSummaryDto(
                row.id(), row.userId(), row.userDisplayName(), row.status(), row.priority(),
                row.assignedStaffUserId(), row.nextPublicSequence(),
                row.staffLastReadSequence(), row.waitingSince(),
                row.lastPublicMessageAt(), row.lastPublicMessageSenderType(),
                row.createdAt());
    }

    private StaffConversationDetailDto toDetailDto(ConversationRow row, long myReadSequence) {
        return new StaffConversationDetailDto(
                row.id(), row.userId(), row.userDisplayName(), row.status(), row.priority(),
                row.assignedStaffUserId(), row.nextPublicSequence(),
                row.userLastReadSequence(), row.staffLastReadSequence(),
                myReadSequence, row.waitingSince(), row.firstStaffResponseAt(),
                row.lastActivityAt(), row.lastPublicMessageAt(),
                row.lastPublicMessageSenderType(), row.closedAt(),
                row.closedByType(), row.createdAt(), row.updatedAt());
    }

    private SupportInternalNoteDto toNoteDto(NoteRow row) {
        return new SupportInternalNoteDto(
                row.id(), row.conversationId(), row.staffUserId(),
                row.staffDisplayName(), row.body(), row.createdAt());
    }

    private record UploadedAttachment(
            String bucket, String path, String fileName,
            String contentType, long sizeBytes,
            String attachmentKind, Long durationMs) {}

    private static final Set<String> VALID_AUDIO_EXTS = Set.of("m4a", "mp4", "aac", "mp3", "wav", "webm");

    private static String fileExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase() : "";
    }
}
