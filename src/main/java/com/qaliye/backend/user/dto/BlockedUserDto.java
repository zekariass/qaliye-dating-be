package com.qaliye.backend.user.dto;

import java.util.UUID;

public record BlockedUserDto(
        UUID id,
        String displayName,
        BlockedUserAddressDto address,
        String primaryPhotoUrl,
        UUID primaryPhotoId
) {}
