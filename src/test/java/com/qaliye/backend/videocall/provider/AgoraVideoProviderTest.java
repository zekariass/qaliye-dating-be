package com.qaliye.backend.videocall.provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgoraVideoProviderTest {

    private VideoProperties props;
    private AgoraVideoProvider provider;

    @BeforeEach
    void setUp() {
        props = new VideoProperties();
        // Agora accepts only 32-char hex strings — anything else yields ""
        props.getAgora().setAppId("0123456789abcdef0123456789abcdef");
        props.getAgora().setAppCertificate("abcdef0123456789abcdef0123456789");
        props.getAgora().setTokenTtlSeconds(3600);
        provider = new AgoraVideoProvider(props);
    }

    @Test
    void name_isConfigKey() {
        assertThat(provider.name()).isEqualTo("AGORA");
    }

    @Test
    void channelFor_isDeterministicAndOpaque() {
        UUID callId = UUID.randomUUID();
        assertThat(provider.channelFor(callId))
                .isEqualTo("vcr_" + callId)
                .isEqualTo(provider.channelFor(callId));
        assertThat(provider.channelFor(UUID.randomUUID()))
                .isNotEqualTo(provider.channelFor(callId));
    }

    @Test
    void uidFor_isStablePerCallPerUser() {
        UUID callId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        long uid = AgoraVideoProvider.uidFor(callId, userId);

        assertThat(uid).isBetween(0L, 0xFFFF_FFFFL);
        assertThat(AgoraVideoProvider.uidFor(callId, userId)).isEqualTo(uid);
        // same user in a different call gets a different uid
        assertThat(AgoraVideoProvider.uidFor(UUID.randomUUID(), userId))
                .isNotEqualTo(uid);
    }

    @Test
    void issueJoin_mintsSessionWithTokenAndExpiry() {
        UUID callId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        var session = provider.issueJoin(callId, userId, false, null);

        assertThat(session.channelName()).isEqualTo("vcr_" + callId);
        assertThat(session.token()).isNotBlank();
        assertThat(session.uid()).isEqualTo(AgoraVideoProvider.uidFor(callId, userId));
        assertThat(session.expiresAt()).isAfter(OffsetDateTime.now().plusMinutes(59));
    }

    @Test
    void issueJoin_notAfterBeforeTtl_clampsExpiryToDeadline() {
        UUID callId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        OffsetDateTime deadline = OffsetDateTime.now().plusMinutes(10);

        var session = provider.issueJoin(callId, userId, false, deadline);

        // expires_at IS the call deadline — both as token privilege expiry
        // and as the value the client's countdown renders.
        assertThat(session.expiresAt().toInstant())
                .isEqualTo(deadline.toInstant());
        assertThat(session.expiresAt())
                .isBefore(OffsetDateTime.now().plusMinutes(11));
    }

    @Test
    void issueJoin_notAfterBeyondTtl_usesConfiguredTtl() {
        UUID callId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        OffsetDateTime farFuture = OffsetDateTime.now().plusHours(5);

        var session = provider.issueJoin(callId, userId, false, farFuture);

        // Max TTL (3600s) wins — tokens never live past the configured cap.
        assertThat(session.expiresAt())
                .isBefore(OffsetDateTime.now().plusHours(2))
                .isAfter(OffsetDateTime.now().plusMinutes(59));
    }

    @Test
    void issueJoin_missingCredentials_failsFast() {
        props.getAgora().setAppId("");
        assertThatThrownBy(() -> provider.issueJoin(UUID.randomUUID(), UUID.randomUUID(), false, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app-id");
    }
}
