package com.qaliye.backend.discovery.cursor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaliye.backend.discovery.config.DiscoveryProperties;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DiscoveryCursorCodecTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DiscoveryProperties props = mock(DiscoveryProperties.class);

    DiscoveryCursorCodecTest() {
        DiscoveryProperties.Cursor cursorCfg = mock(DiscoveryProperties.Cursor.class);
        when(props.getCursor()).thenReturn(cursorCfg);
        when(cursorCfg.maxAgeMinutes()).thenReturn(60);
    }

    @Test
    void roundTrip_withScoreAndUserId_preservesValues() {
        DiscoveryCursorCodec codec = new DiscoveryCursorCodec(objectMapper, props);
        UUID userId = UUID.randomUUID();
        double score = 1234.56;

        String token = codec.encode(score, userId);
        DiscoveryCursorCodec.CursorState state = codec.decode(token);

        assertThat(state.reset()).isFalse();
        assertThat(state.lastScore()).isEqualTo(score);
        assertThat(state.lastUserId()).isEqualTo(userId);
    }

    @Test
    void decode_nullCursor_returnsFresh() {
        DiscoveryCursorCodec codec = new DiscoveryCursorCodec(objectMapper, props);

        DiscoveryCursorCodec.CursorState state = codec.decode(null);

        assertThat(state.reset()).isFalse();
        assertThat(state.lastScore()).isNull();
        assertThat(state.lastUserId()).isNull();
    }

    @Test
    void decode_blankCursor_returnsFresh() {
        DiscoveryCursorCodec codec = new DiscoveryCursorCodec(objectMapper, props);

        DiscoveryCursorCodec.CursorState state = codec.decode("   ");

        assertThat(state.reset()).isFalse();
        assertThat(state.lastScore()).isNull();
        assertThat(state.lastUserId()).isNull();
    }

    @Test
    void decode_expiredCursor_resetsToFresh() {
        DiscoveryCursorCodec codec = new DiscoveryCursorCodec(objectMapper, props);
        UUID userId = UUID.randomUUID();
        String token = codec.encode(100.0, userId);

        // Simulate expiry by using a codec with 0-minute max age
        DiscoveryProperties.Cursor zeroMinCfg = mock(DiscoveryProperties.Cursor.class);
        when(zeroMinCfg.maxAgeMinutes()).thenReturn(0);
        when(props.getCursor()).thenReturn(zeroMinCfg);
        DiscoveryCursorCodec expiredCodec = new DiscoveryCursorCodec(objectMapper, props);

        DiscoveryCursorCodec.CursorState state = expiredCodec.decode(token);

        assertThat(state.reset()).isTrue();
        assertThat(state.lastScore()).isNull();
        assertThat(state.lastUserId()).isNull();
    }

    @Test
    void decode_malformedToken_returnsFresh() {
        DiscoveryCursorCodec codec = new DiscoveryCursorCodec(objectMapper, props);

        DiscoveryCursorCodec.CursorState state = codec.decode("not-a-valid-cursor");

        assertThat(state.lastScore()).isNull();
        assertThat(state.lastUserId()).isNull();
    }
}
