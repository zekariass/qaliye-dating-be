package com.qaliye.backend.actions;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class SwipeRequest {

    @NotNull
    private UUID targetUserId;

    @NotBlank
    @Pattern(regexp = "^(LIKE|PASS|SUPERLIKE)$", message = "must be LIKE, PASS, or SUPERLIKE")
    private String actionType;

    @NotNull
    private UUID clientActionId;
}
