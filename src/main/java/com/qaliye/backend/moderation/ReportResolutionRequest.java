package com.qaliye.backend.moderation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ReportResolutionRequest {

    @NotBlank
    @Pattern(regexp = "^(RESOLVED_NO_ACTION|RESOLVED_BANNED)$",
             message = "must be RESOLVED_NO_ACTION or RESOLVED_BANNED")
    private String resolution;

    private String banReason;
}
