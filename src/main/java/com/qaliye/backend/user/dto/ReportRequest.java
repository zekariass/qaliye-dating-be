package com.qaliye.backend.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ReportRequest(
        @NotBlank
        @Pattern(regexp = "FAKE_PROFILE|HARASSMENT|HATE_SPEECH|INAPPROPRIATE_CONTENT|SCAM|UNDERAGE|VIOLENCE_OR_THREATS|PRIVACY_VIOLATION|OFF_PLATFORM_SOLICITATION|SPAM|OTHER")
        String reportType,
        @Size(max = 2000) String description
) {}
