package com.qaliye.backend.chat.dto;

import java.util.UUID;

public record ChatMatchMetadataDto(
        UUID matchId,
        String status,
        String matchSource,
        ParticipantDto participant,
        ReceiptStateDto receiptState
) {}
