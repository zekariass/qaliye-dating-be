package com.qaliye.backend.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class UpdateUserRoleRequest {

    @NotBlank
    @Pattern(regexp = "^(USER|MODERATOR|ADMIN|TEST)$",
             message = "role must be USER, MODERATOR, ADMIN, or TEST")
    private String role;

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
}
