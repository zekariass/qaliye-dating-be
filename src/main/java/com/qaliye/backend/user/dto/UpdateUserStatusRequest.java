package com.qaliye.backend.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class UpdateUserStatusRequest {

    @NotBlank
    @Pattern(regexp = "^(ACTIVE|SUSPENDED|DEACTIVATED|BANNED)$",
             message = "status must be ACTIVE, SUSPENDED, DEACTIVATED, or BANNED")
    private String status;

    private String reason;

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
