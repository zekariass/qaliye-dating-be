package com.qaliye.backend.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class UpdateUserRoleRequest {

    @NotBlank
    @Pattern(regexp = "^(USER|MODERATOR|ADMIN)$",
             message = "role must be USER, MODERATOR, or ADMIN")
    private String role;

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
}
