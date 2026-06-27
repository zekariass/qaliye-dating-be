package com.qaliye.backend.chat.service;

import com.qaliye.backend.chat.dto.ChatMessageDto;
import com.qaliye.backend.chat.repository.ChatMessageRepository.MessageRow;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;

@Component
public class ChatDtoMapper {

    public ChatMessageDto toMessageDto(MessageRow row, long recipientReadSeq, long recipientDeliveredSeq,
                                       UUID callerId) {
        String deliveryStatus = null;
        if (row.senderUserId().equals(callerId)) {
            deliveryStatus = computeDeliveryStatus(row.sequenceNumber(), recipientDeliveredSeq, recipientReadSeq);
        }
        return new ChatMessageDto(
                row.id(),
                row.matchId(),
                row.sequenceNumber(),
                row.senderUserId(),
                row.messageType(),
                row.body(),
                deliveryStatus,
                toInstant(row.createdAt())
        );
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
