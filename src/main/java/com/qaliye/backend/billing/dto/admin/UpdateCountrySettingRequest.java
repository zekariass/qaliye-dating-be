package com.qaliye.backend.billing.dto.admin;

public record UpdateCountrySettingRequest(
        String countryCode,
        Boolean subscriptionEnabled,
        Boolean creditsEnabled,
        Boolean identityVerificationRequired
) {}
