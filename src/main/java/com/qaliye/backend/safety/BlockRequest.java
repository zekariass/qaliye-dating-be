package com.qaliye.backend.safety;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class BlockRequest {

    @NotNull
    private UUID blockedUserId;
}
