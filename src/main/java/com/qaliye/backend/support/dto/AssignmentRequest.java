package com.qaliye.backend.support.dto;

import java.util.UUID;

public record AssignmentRequest(
        UUID assignedStaffUserId
) {}
