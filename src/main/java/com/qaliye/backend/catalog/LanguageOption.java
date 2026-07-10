package com.qaliye.backend.catalog;

import java.util.UUID;

public record LanguageOption(
        UUID id,
        String code,
        String countryCode,
        String name,
        String nativeName
) {}
