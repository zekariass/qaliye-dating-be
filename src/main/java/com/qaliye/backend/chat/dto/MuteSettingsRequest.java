package com.qaliye.backend.chat.dto;

import java.time.Instant;

public class MuteSettingsRequest {

    private Instant mutedUntil;

    public Instant getMutedUntil() { return mutedUntil; }
    public void setMutedUntil(Instant mutedUntil) { this.mutedUntil = mutedUntil; }
}
