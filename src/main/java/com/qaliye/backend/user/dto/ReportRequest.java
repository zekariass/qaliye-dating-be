package com.qaliye.backend.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ReportRequest(
        @NotBlank
        @Pattern(regexp = "FAKE_PROFILE|HARASSMENT|INAPPROPRIATE_PHOTO|SCAM|UNDERAGE|OFF_PLATFORM_SOLICITATION|OTHER")
        String reportType,
        @Size(max = 2000) String description
) {}
