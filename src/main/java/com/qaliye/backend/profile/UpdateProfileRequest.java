package com.qaliye.backend.profile;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record UpdateProfileRequest(
        @NotBlank @Size(min = 2, max = 100) String displayName,
        @NotBlank String gender,
        @NotNull LocalDate dateOfBirth,
        @NotBlank String residencyType,
        @NotBlank String relationshipIntention
) {}
