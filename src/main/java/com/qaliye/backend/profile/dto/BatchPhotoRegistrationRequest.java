package com.qaliye.backend.profile.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record BatchPhotoRegistrationRequest(
        @NotEmpty @Size(max = 6) @Valid List<PhotoRegistrationRequest> photos
) {}
