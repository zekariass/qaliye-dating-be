package com.qaliye.backend.verification;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class SubmitVerificationRequest {

    @NotBlank
    private String storagePath;
}
