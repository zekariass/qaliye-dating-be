package com.qaliye.backend.verification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ReviewVerificationRequest {

    @NotBlank
    @Pattern(regexp = "^(APPROVED|REJECTED)$", message = "must be APPROVED or REJECTED")
    private String decision;

    private String rejectionReason;
}
