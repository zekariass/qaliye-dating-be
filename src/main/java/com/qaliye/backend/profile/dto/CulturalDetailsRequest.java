package com.qaliye.backend.profile.dto;

import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CulturalDetailsRequest(
        @Size(max = 10, message = "Maximum 10 ethnicity selections") List<UUID> ethnicityIds,
        @Size(max = 200) String ethnicityOtherText,
        @Size(max = 20, message = "Maximum 20 language selections") List<UUID> languageIds
) {}
