package com.qaliye.backend.chat.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public class MarkReceiptRequest {

    @NotNull
    @Min(0)
    private Long upToSequence;

    public Long getUpToSequence() { return upToSequence; }
    public void setUpToSequence(Long upToSequence) { this.upToSequence = upToSequence; }
}
