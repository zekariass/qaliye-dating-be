package com.qaliye.backend.support.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AddNoteRequest(
        @NotNull UUID clientNoteId,
        @NotBlank String body
) {}
