package com.qaliye.backend.payments;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class AdminTransactionReviewRequest {

    @NotBlank
    @Pattern(regexp = "^(COMPLETED|FAILED)$", message = "must be COMPLETED or FAILED")
    private String status;

    private String adminNotes;
}
