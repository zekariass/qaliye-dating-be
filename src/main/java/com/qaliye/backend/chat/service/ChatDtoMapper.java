package com.qaliye.backend.chat.service;

import com.qaliye.backend.chat.dto.ChatAttachmentDto;
import com.qaliye.backend.chat.dto.ChatMessageDto;
import com.qaliye.backend.chat.repository.ChatAttachmentRepository.AttachmentRow;
import com.qaliye.backend.chat.repository.ChatMessageRepository.MessageRow;
import com.qaliye.backend.storage.SupabaseStorageService;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Component
public class ChatDtoMapper {

    private final SupabaseStorageService storageService;
    private final com.qaliye.backend.chat.config.ChatProperties chatProps;

    public ChatDtoMapper(SupabaseStorageService storageService,
                         com.qaliye.backend.chat.config.ChatProperties chatProps) {
        this.storageService = storageService;
        this.chatProps = chatProps;
    }

    public ChatMessageDto toMessageDto(MessageRow row, long recipientReadSeq, long recipientDeliveredSeq,
                                       UUID callerId) {
        return toMessageDto(row, recipientReadSeq, recipientDeliveredSeq, callerId, List.of());
    }

    public ChatMessageDto toMessageDto(MessageRow row, long recipientReadSeq, long recipientDeliveredSeq,
                                       UUID callerId, List<AttachmentRow> attachments) {
        String deliveryStatus = null;
        if (row.senderUserId().equals(callerId)) {
            deliveryStatus = computeDeliveryStatus(row.sequenceNumber(), recipientDeliveredSeq, recipientReadSeq);
        }
        List<ChatAttachmentDto> attDtos = attachments.stream()
                .map(a -> toAttachmentDto(a))
                .toList();
        return new ChatMessageDto(
                row.id(),
                row.matchId(),
                row.sequenceNumber(),
                row.senderUserId(),
                row.messageType(),
                row.body(),
                deliveryStatus,
                toInstant(row.createdAt()),
                attDtos
        );
    }

    public ChatAttachmentDto toAttachmentDto(AttachmentRow a) {
        String signedUrl = storageService.generateSignedUrl(
                a.storageBucket(), a.storagePath(),
                chatProps.getAttachment().getSignedUrlTtlSeconds());
        return new ChatAttachmentDto(
                a.id(), a.messageId(), a.attachmentType(),
                a.fileName(), a.contentType(), a.fileSizeBytes(),
                a.durationMs(), signedUrl, toInstant(a.createdAt()));
    }

    private String computeDeliveryStatus(long seqNum, long recipientDelivered, long recipientRead) {
        if (recipientRead >= seqNum)      return "READ";
        if (recipientDelivered >= seqNum) return "DELIVERED";
        return "SENT";
    }

    private java.time.Instant toInstant(OffsetDateTime odt) {
        return odt != null ? odt.toInstant() : null;
    }
}
