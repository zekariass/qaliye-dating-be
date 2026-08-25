package com.qaliye.backend.discovery.dto;

import java.util.UUID;

public record UserProfileBrief(
        UUID id,
        String displayName,
        String photoUrl,
        boolean deleted
) {}
