package com.qaliye.backend.chat.cursor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaliye.backend.chat.config.ChatProperties;
import com.qaliye.backend.chat.exception.InvalidCursorException;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class ChatCursorCodec {

    private static final int VERSION = 1;
    private static final String HMAC_ALGO = "HmacSHA256";

    private final ObjectMapper objectMapper;
    private final ChatProperties props;

    public ChatCursorCodec(ObjectMapper objectMapper, ChatProperties props) {
        this.objectMapper = objectMapper;
        this.props = props;
    }

    public record CursorState(
            String filter,
            Instant snapshotAt,
            Instant lastMessageAt,
            Instant matchedAt,
            UUID matchId
    ) {}

    private record CursorPayload(
            int version,
            String filter,
            String snapshotAt,
            String lastMessageAt,
            String matchedAt,
            String matchId,
            String hmac
    ) {}

    public String encode(CursorState state) {
        try {
            String lastMsgAt = state.lastMessageAt() != null ? state.lastMessageAt().toString() : null;
            CursorPayload unsigned = new CursorPayload(
                    VERSION,
                    state.filter(),
                    state.snapshotAt().toString(),
                    lastMsgAt,
                    state.matchedAt().toString(),
                    state.matchId().toString(),
                    ""
            );
            String dataJson = objectMapper.writeValueAsString(unsigned);
            String hmac = computeHmac(dataJson);
            CursorPayload signed = new CursorPayload(
                    VERSION,
                    state.filter(),
                    state.snapshotAt().toString(),
                    lastMsgAt,
                    state.matchedAt().toString(),
                    state.matchId().toString(),
                    hmac
            );
            String signedJson = objectMapper.writeValueAsString(signed);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(signedJson.getBytes(StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to encode cursor", e);
        }
    }

    public CursorState decode(String cursor, String requestedFilter) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            CursorPayload payload = objectMapper.readValue(decoded, CursorPayload.class);

            if (payload.version() != VERSION) {
                throw new InvalidCursorException();
            }

            String receivedHmac = payload.hmac();
            CursorPayload withoutHmac = new CursorPayload(
                    payload.version(), payload.filter(), payload.snapshotAt(),
                    payload.lastMessageAt(), payload.matchedAt(), payload.matchId(), ""
            );
            String dataJson = objectMapper.writeValueAsString(withoutHmac);
            String expectedHmac = computeHmac(dataJson);

            if (!constantTimeEquals(expectedHmac, receivedHmac)) {
                throw new InvalidCursorException();
            }

            if (!requestedFilter.equals(payload.filter())) {
                throw new InvalidCursorException();
            }

            Instant lastMsgAt = payload.lastMessageAt() != null
                    ? Instant.parse(payload.lastMessageAt()) : null;

            return new CursorState(
                    payload.filter(),
                    Instant.parse(payload.snapshotAt()),
                    lastMsgAt,
                    Instant.parse(payload.matchedAt()),
                    UUID.fromString(payload.matchId())
            );
        } catch (InvalidCursorException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidCursorException();
        }
    }

    private String computeHmac(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            SecretKeySpec keySpec = new SecretKeySpec(
                    props.getCursor().getHmacSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGO);
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hmacBytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC computation failed", e);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        if (aBytes.length != bBytes.length) return false;
        int result = 0;
        for (int i = 0; i < aBytes.length; i++) {
            result |= aBytes[i] ^ bBytes[i];
        }
        return result == 0;
    }
}
