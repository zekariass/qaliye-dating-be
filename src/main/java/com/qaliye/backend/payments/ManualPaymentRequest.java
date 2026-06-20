package com.qaliye.backend.payments;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ManualPaymentRequest {

    @NotBlank
    @Pattern(regexp = "^(CHAPA|TELEBIRR|CBE_BIRR|BANK_TRANSFER)$",
             message = "must be CHAPA, TELEBIRR, CBE_BIRR, or BANK_TRANSFER")
    private String provider;

    @NotNull
    @Min(1)
    private Integer amountMinorUnits;

    @NotBlank
    private String currency;

    @NotBlank
    @Pattern(regexp = "^(SUBSCRIPTION|PROFILE_BOOST|SUPER_LIKE_PACK|REWIND_PACK)$",
             message = "must be SUBSCRIPTION, PROFILE_BOOST, SUPER_LIKE_PACK, or REWIND_PACK")
    private String paymentPurpose;

    @NotBlank
    private String receiptStorageBucket;

    @NotBlank
    private String receiptStoragePath;
}
