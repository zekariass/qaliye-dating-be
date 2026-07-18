package com.qaliye.backend.support.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.qaliye.backend.storage.SupabaseStorageService;
import com.qaliye.backend.support.SupportProperties;
import com.qaliye.backend.support.dto.SupportAttachmentDto;
import com.qaliye.backend.support.dto.SupportConversationDto;
import com.qaliye.backend.support.dto.SupportMessageDto;
import com.qaliye.backend.support.dto.SupportMessagePageDto;
import com.qaliye.backend.support.exception.SupportRpcException;
import com.qaliye.backend.support.repository.SupportConversationRepository;
import com.qaliye.backend.support.repository.SupportConversationRepository.ConversationRow;
import com.qaliye.backend.support.repository.SupportMessageRepository;
import com.qaliye.backend.support.repository.SupportMessageRepository.AttachmentRow;
import com.qaliye.backend.support.repository.SupportMessageRepository.MessageRow;
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
public class SupportConversationService {

    private static final Logger log = LoggerFactory.getLogger(SupportConversationService.class);

    private final SupportConversationRepository convRepo;
    private final SupportMessageRepository msgRepo;
    private final SupabaseStorageService storageService;
    private final SupportProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SupportConversationService(
            SupportConversationRepository convRepo,
            SupportMessageRepository msgRepo,
            SupabaseStorageService storageService,
            SupportProperties props) {
        this.convRepo = convRepo;
        this.msgRepo = msgRepo;
        this.storageService = storageService;
        this.props = props;
    }

    public SupportConversationDto getConversation(UUID userId) {
        ConversationRow row = convRepo.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Support conversation not found"));
        return toConversationDto(row);
    }

    public SupportMessagePageDto listMessages(UUID userId, Long beforeSequence, int limit) {
        ConversationRow conv = convRepo.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Support conversation not found"));

        int capped = Math.min(Math.max(limit, 1), 50);
        List<MessageRow> messages = msgRepo.findMessages(conv.id(), beforeSequence, capped);

        List<UUID> messageIds = messages.stream().map(MessageRow::id).toList();
        Map<UUID, List<AttachmentRow>> attachsByMsg = groupAttachments(messageIds);

        List<SupportMessageDto> dtos = messages.stream()
                .map(m -> toMessageDto(m, attachsByMsg.getOrDefault(m.id(), List.of())))
                .toList();

        Long nextCursor = messages.size() == capped
                ? messages.get(messages.size() - 1).sequenceNumber()
                : null;

        return new SupportMessagePageDto(dtos, nextCursor);
    }

    public SupportMessageDto sendMessage(
            UUID userId,
            UUID clientMessageId,
            String body,
            List<MultipartFile> files,
            List<Long> durations) {

        ConversationRow conv = convRepo.findByUserId(userId)
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
                String contentType = normalizeAudioContentType(file.getContentType(), file.getOriginalFilename());
                String sanitizedName = sanitizeFileName(file.getOriginalFilename());
                String storagePath = "support/" + conv.id() + "/" + clientMessageId
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
                String kind = detectAttachmentKind(contentType);
                Long durationMs = (durations != null && i < durations.size()) ? durations.get(i) : null;
                if ("VOICE".equals(kind) && durationMs == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Voice attachment requires duration_ms");
                }
                if (!"VOICE".equals(kind)) {
                    durationMs = null;
                }
                uploaded.add(new UploadedAttachment(
                        props.getAttachmentsBucket(),
                        storagePath,
                        sanitizedName,
                        contentType,
                        file.getSize(),
                        kind,
                        durationMs
                ));
            }

            String attachmentsJson = buildAttachmentsJson(uploaded);
            MessageRow msg;
            try {
                msg = msgRepo.callAppendUserMessage(
                        userId, clientMessageId,
                        body != null ? body.strip() : null,
                        attachmentsJson, "{}");
            } catch (Exception e) {
                throw SupportRpcException.translate(e);
            }

            List<AttachmentRow> attachments = msgRepo.findAttachmentsByMessageId(msg.id());
            log.info("action=send_support_user_message userId={} conversationId={} messageId={} attachments={}",
                    userId, conv.id(), msg.id(), attachments.size());
            return toMessageDto(msg, attachments);

        } catch (Exception ex) {
            cleanupUploads(uploaded);
            throw ex;
        }
    }

    public void markRead(UUID userId, long lastReadSequence) {
        ConversationRow conv = convRepo.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Support conversation not found"));
        try {
            convRepo.callMarkReadByUser(conv.id(), userId, lastReadSequence);
        } catch (Exception e) {
            throw SupportRpcException.translate(e);
        }
        log.info("action=mark_support_read userId={} conversationId={} seq={}", userId, conv.id(), lastReadSequence);
    }

    public void close(UUID userId) {
        ConversationRow conv = convRepo.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Support conversation not found"));
        try {
            convRepo.callCloseByUser(conv.id(), userId);
        } catch (Exception e) {
            throw SupportRpcException.translate(e);
        }
        log.info("action=close_support_conversation_by_user userId={} conversationId={}", userId, conv.id());
    }

    public String getAttachmentDownloadUrl(UUID userId, UUID attachmentId) {
        UUID ownerUserId = msgRepo.findConversationUserIdForAttachment(attachmentId);
        if (ownerUserId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment not found");
        }
        if (!ownerUserId.equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
        AttachmentRow att = msgRepo.findAttachmentWithConversationUserId(attachmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment not found"));

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
            String normalizedCt = normalizeAudioContentType(ct, file.getOriginalFilename());
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
                validateAudioExtension(file.getOriginalFilename(), normalizedCt);
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
        ArrayNode array = objectMapper.createArrayNode();
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

    public static String sanitizeFileName(String raw) {
        if (raw == null || raw.isBlank()) return "attachment";
        String name = raw.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        return name.length() > 200 ? name.substring(0, 200) : name;
    }

    private Map<UUID, List<AttachmentRow>> groupAttachments(List<UUID> messageIds) {
        if (messageIds.isEmpty()) return Map.of();
        return msgRepo.findAttachmentsByMessageIds(messageIds).stream()
                .collect(Collectors.groupingBy(AttachmentRow::messageId));
    }

    private SupportConversationDto toConversationDto(ConversationRow row) {
        return new SupportConversationDto(
                row.id(),
                row.status(),
                row.userLastReadSequence(),
                row.nextPublicSequence(),
                row.lastPublicMessageAt(),
                row.lastPublicMessageSenderType(),
                row.closedAt(),
                row.createdAt()
        );
    }

    public SupportMessageDto toMessageDto(MessageRow msg, List<AttachmentRow> attachments) {
        List<SupportAttachmentDto> attDtos = attachments.stream()
                .map(a -> {
                    String signedUrl = storageService.generateSignedUrl(
                            a.storageBucket(), a.storagePath(), props.getAttachmentSignedUrlTtlSeconds());
                    return new SupportAttachmentDto(
                            a.id(), a.messageId(), a.fileName(),
                            a.contentType(), a.fileSizeBytes(), signedUrl,
                            a.attachmentKind(), a.durationMs(), a.createdAt());
                })
                .toList();
        return new SupportMessageDto(
                msg.id(), msg.conversationId(), msg.sequenceNumber(),
                msg.senderType(), msg.senderDisplayName(), msg.body(), msg.createdAt(), attDtos);
    }

    private record UploadedAttachment(
            String bucket, String path, String fileName,
            String contentType, long sizeBytes,
            String attachmentKind, Long durationMs) {}

    public static String normalizeAudioContentType(String contentType, String filename) {
        if (contentType == null) return null;
        String lower = contentType.toLowerCase();
        String ext = fileExtension(filename);
        if ("audio/x-m4a".equals(lower) || ("audio/mp4".equals(lower) && "m4a".equals(ext))) {
            return "audio/m4a";
        }
        return lower;
    }

    public static String detectAttachmentKind(String contentType) {
        if (contentType == null) return "OTHER";
        if (contentType.startsWith("audio/")) return "VOICE";
        if (contentType.startsWith("image/")) return "IMAGE";
        if ("application/pdf".equals(contentType)) return "DOCUMENT";
        if (contentType.startsWith("text/")) return "TEXT";
        return "OTHER";
    }

    private static String fileExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase() : "";
    }

    private static final Set<String> VALID_AUDIO_EXTS = Set.of("m4a", "mp4", "aac", "mp3", "wav", "webm");

    private void validateAudioExtension(String filename, String contentType) {
        String ext = fileExtension(filename);
        if (!VALID_AUDIO_EXTS.contains(ext)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Audio file extension not allowed: " + ext);
        }
    }

    public static List<Long> parseDurations(String durationsJson) {
        if (durationsJson == null || durationsJson.isBlank()) return null;
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            JsonNode node = om.readTree(durationsJson);
            if (!node.isArray()) return null;
            List<Long> result = new ArrayList<>();
            for (JsonNode el : node) {
                result.add(el.isNull() ? null : el.asLong());
            }
            return result;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid durations format: expected JSON array of integers");
        }
    }
}
