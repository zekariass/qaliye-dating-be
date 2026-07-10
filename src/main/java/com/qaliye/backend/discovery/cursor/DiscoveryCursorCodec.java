package com.qaliye.backend.discovery.cursor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaliye.backend.discovery.config.DiscoveryProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Component
public class DiscoveryCursorCodec {

    private final ObjectMapper objectMapper;
    private final DiscoveryProperties props;

    public DiscoveryCursorCodec(ObjectMapper objectMapper, DiscoveryProperties props) {
        this.objectMapper = objectMapper;
        this.props = props;
    }

    public record CursorState(Instant generatedAt, boolean reset,
                              Double lastScore, UUID lastUserId) {
        public static CursorState fresh() {
            return new CursorState(Instant.now(), false, null, null);
        }
    }

    private record CursorPayload(String generatedAt,
                                  Double lastScore, String lastUserId) {}

    public String encode(Double lastScore, UUID lastUserId) {
        try {
            String lastUserIdStr = lastUserId != null ? lastUserId.toString() : null;
            String json = objectMapper.writeValueAsString(
                    new CursorPayload(Instant.now().toString(), lastScore, lastUserIdStr));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to encode cursor", e);
        }
    }

    public CursorState decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return CursorState.fresh();
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            CursorPayload payload = objectMapper.readValue(decoded, CursorPayload.class);
            Instant generatedAt = Instant.parse(payload.generatedAt());

            boolean tooOld = generatedAt.isBefore(
                    Instant.now().minusSeconds(props.getCursor().maxAgeMinutes() * 60L));

            if (tooOld) {
                return new CursorState(Instant.now(), true, null, null);
            }
            UUID lastUserId = payload.lastUserId() != null ? UUID.fromString(payload.lastUserId()) : null;
            return new CursorState(generatedAt, false,
                    payload.lastScore(), lastUserId);
        } catch (Exception e) {
            return CursorState.fresh();
        }
    }
}
