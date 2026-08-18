package com.qaliye.backend.billing.dto.admin;

import jakarta.validation.constraints.NotBlank;

public record CreateCountrySettingRequest(
        @NotBlank String countryCode,
        Boolean subscriptionEnabled,
        Boolean creditsEnabled,
        Boolean identityVerificationRequired
) {}
