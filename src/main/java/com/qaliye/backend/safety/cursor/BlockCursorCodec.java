package com.qaliye.backend.safety.cursor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Component
public class BlockCursorCodec {

    private final ObjectMapper objectMapper;

    public BlockCursorCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record CursorState(Instant blockedAt, UUID lastId) {
        public static CursorState first() {
            return null;
        }
    }

    private record CursorPayload(String blockedAt, String lastId) {}

    public String encode(CursorState state) {
        if (state == null || state.blockedAt() == null || state.lastId() == null) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(
                    new CursorPayload(state.blockedAt().toString(), state.lastId().toString()));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to encode block cursor", e);
        }
    }

    public CursorState decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            CursorPayload payload = objectMapper.readValue(decoded, CursorPayload.class);
            if (payload.blockedAt() == null || payload.lastId() == null) {
                throw new IllegalArgumentException("Malformed cursor");
            }
            return new CursorState(Instant.parse(payload.blockedAt()), UUID.fromString(payload.lastId()));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid or malformed cursor", e);
        }
    }
}
