package com.qaliye.backend.support.dto;

import java.util.List;

public record SupportMessagePageDto(
        List<SupportMessageDto> messages,
        Long nextBeforeSequence
) {}
