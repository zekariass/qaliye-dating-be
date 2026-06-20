package com.qaliye.backend.discovery.dto;

import java.util.UUID;

public record DiscoveryPromptAnswerDto(
        UUID promptId,
        String promptText,
        String answerText
) {}
