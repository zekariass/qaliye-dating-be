package com.qaliye.backend.chat.dto;

public record ReceiptStateDto(
        long myLastDeliveredSequence,
        long myLastReadSequence,
        long participantLastDeliveredSequence,
        long participantLastReadSequence
) {}
