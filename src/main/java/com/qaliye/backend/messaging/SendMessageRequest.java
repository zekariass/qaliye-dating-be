package com.qaliye.backend.messaging;

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
public class SendMessageRequest {

    @NotNull
    private UUID matchId;

    @NotNull
    private UUID clientMessageId;

    @NotBlank
    @Pattern(regexp = "^(TEXT|IMAGE|VOICE|ICEBREAKER|PROMPT_REPLY)$",
             message = "must be TEXT, IMAGE, VOICE, ICEBREAKER, or PROMPT_REPLY")
    private String messageType;

    private String body;

    private String storageBucket;

    private String storagePath;
}
