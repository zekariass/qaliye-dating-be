package com.qaliye.backend.discovery.cursor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaliye.backend.discovery.config.DiscoveryProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

@Component
public class DiscoveryCursorCodec {

    private final ObjectMapper objectMapper;
    private final DiscoveryProperties props;

    public DiscoveryCursorCodec(ObjectMapper objectMapper, DiscoveryProperties props) {
        this.objectMapper = objectMapper;
        this.props = props;
    }

    public record CursorState(int offset, String locationFilter, Instant generatedAt, boolean reset) {
        public static CursorState fresh(String locationFilter) {
            return new CursorState(0, locationFilter, Instant.now(), false);
        }
    }

    private record CursorPayload(int offset, String locationFilter, String generatedAt) {}

    public String encode(int offset, String locationFilter) {
        try {
            String json = objectMapper.writeValueAsString(
                    new CursorPayload(offset, locationFilter, Instant.now().toString()));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to encode cursor", e);
        }
    }

    public CursorState decode(String cursor, String requestedFilter) {
        if (cursor == null || cursor.isBlank()) {
            return CursorState.fresh(requestedFilter);
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            CursorPayload payload = objectMapper.readValue(decoded, CursorPayload.class);
            Instant generatedAt = Instant.parse(payload.generatedAt());

            boolean tooOld = generatedAt.isBefore(
                    Instant.now().minusSeconds(props.getCursor().maxAgeMinutes() * 60L));
            boolean filterMismatch = !requestedFilter.equals(payload.locationFilter());

            if (tooOld || filterMismatch) {
                return new CursorState(0, requestedFilter, Instant.now(), true);
            }
            return new CursorState(payload.offset(), payload.locationFilter(), generatedAt, false);
        } catch (Exception e) {
            return CursorState.fresh(requestedFilter);
        }
    }
}
