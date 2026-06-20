package com.qaliye.backend.profile;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record PatchPhotoRequest(
        @Min(0) @Max(8) Integer photoOrder,
        Boolean isPrimary
) {}
