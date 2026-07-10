package com.qaliye.backend.profile.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record PhotoReorderRequest(
        @NotEmpty List<@Valid PhotoReorderItem> photos
) {}
