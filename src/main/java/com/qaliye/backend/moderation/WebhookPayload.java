package com.qaliye.backend.moderation;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class WebhookPayload {

    private String type;
    private PhotoRecord record;

    @Getter
    @Setter
    @NoArgsConstructor
    public static class PhotoRecord {
        private UUID id;
        private UUID userId;
        private String storagePath;
        private String moderationStatus;
    }
}
