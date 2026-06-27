package com.qaliye.backend.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.qaliye.backend.chat.config.ChatProperties;
import com.qaliye.backend.chat.cursor.ChatCursorCodec;
import com.qaliye.backend.chat.cursor.ChatCursorCodec.CursorState;
import com.qaliye.backend.chat.exception.InvalidCursorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class ChatCursorCodecTest {

    private ChatCursorCodec codec;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.findAndRegisterModules();
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        ChatProperties props = new ChatProperties();
        props.getCursor().setHmacSecret("test-secret-key-for-unit-tests");

        codec = new ChatCursorCodec(mapper, props);
    }

    @Test
    void encodeAndDecodeRoundTrip() {
        CursorState state = new CursorState(
                "ALL",
                Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2025-01-01T12:00:00Z"),
                Instant.parse("2025-01-01T10:00:00Z"),
                UUID.randomUUID()
        );

        String token = codec.encode(state);
        CursorState decoded = codec.decode(token, "ALL");

        assertThat(decoded.filter()).isEqualTo(state.filter());
        assertThat(decoded.lastMessageAt()).isEqualTo(state.lastMessageAt());
        assertThat(decoded.matchedAt()).isEqualTo(state.matchedAt());
        assertThat(decoded.matchId()).isEqualTo(state.matchId());
    }

    @Test
    void decodeNullCursorReturnsNull() {
        assertThat(codec.decode(null, "ALL")).isNull();
        assertThat(codec.decode("", "ALL")).isNull();
        assertThat(codec.decode("  ", "ALL")).isNull();
    }

    @Test
    void rejectsWrongFilter() {
        CursorState state = new CursorState(
                "ALL", Instant.now(), null, Instant.now(), UUID.randomUUID());
        String token = codec.encode(state);
        assertThatThrownBy(() -> codec.decode(token, "UNREAD"))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void rejectsTamperedToken() {
        CursorState state = new CursorState(
                "ALL", Instant.now(), Instant.now(), Instant.now(), UUID.randomUUID());
        String token = codec.encode(state);
        String tampered = token.substring(0, token.length() - 4) + "XXXX";
        assertThatThrownBy(() -> codec.decode(tampered, "ALL"))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void rejectsGarbageToken() {
        assertThatThrownBy(() -> codec.decode("totally-not-a-cursor", "ALL"))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void encodesNullLastMessageAt() {
        CursorState state = new CursorState(
                "ALL", Instant.now(), null, Instant.now(), UUID.randomUUID());
        String token = codec.encode(state);
        CursorState decoded = codec.decode(token, "ALL");
        assertThat(decoded.lastMessageAt()).isNull();
    }

    @Test
    void differentSecretsProduceDifferentHmacs() {
        ChatProperties otherProps = new ChatProperties();
        otherProps.getCursor().setHmacSecret("different-secret");
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.findAndRegisterModules();
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        ChatCursorCodec otherCodec = new ChatCursorCodec(mapper, otherProps);

        CursorState state = new CursorState(
                "ALL", Instant.now(), Instant.now(), Instant.now(), UUID.randomUUID());
        String token = codec.encode(state);

        assertThatThrownBy(() -> otherCodec.decode(token, "ALL"))
                .isInstanceOf(InvalidCursorException.class);
    }
}
