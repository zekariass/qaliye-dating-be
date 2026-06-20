package com.qaliye.backend.moderation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class PhotoReviewRequest {

    @NotBlank
    @Pattern(regexp = "^(APPROVED|REJECTED)$", message = "must be APPROVED or REJECTED")
    private String status;
}
