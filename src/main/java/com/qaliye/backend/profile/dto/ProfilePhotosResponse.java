package com.qaliye.backend.profile.dto;

import java.util.List;

public record ProfilePhotosResponse(
        List<ProfilePhotoDto> photos
) {}
