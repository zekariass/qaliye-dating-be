package com.qaliye.backend.videocall.provider;

import io.agora.media.RtcTokenBuilder2;
import io.agora.media.RtcTokenBuilder2.Role;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * {@link VideoCallProvider} backed by Agora RTC.
 *
 * Bound to the config key {@code AGORA} — active when
 * {@code video.platform=AGORA}. Tokens are minted on demand per participant
 * and never stored; {@link #channelFor} produces a deterministic opaque
 * channel name derived from the video-call-request id.
 */
@Component
public class AgoraVideoProvider implements VideoCallProvider {

    private final VideoProperties props;

    public AgoraVideoProvider(VideoProperties props) {
        this.props = props;
    }

    @Override
    public String name() {
        return "AGORA";
    }

    @Override
    public String channelFor(UUID videoCallRequestId) {
        return "vcr_" + videoCallRequestId;
    }

    @Override
    public JoinSession issueJoin(UUID videoCallRequestId, UUID userId, boolean host) {
        var agora = props.getAgora();
        if (agora.getAppId() == null || agora.getAppId().isBlank()
                || agora.getAppCertificate() == null || agora.getAppCertificate().isBlank()) {
            throw new IllegalStateException(
                    "video.platform=AGORA requires video.agora.app-id and "
                            + "video.agora.app-certificate to be configured.");
        }

        String channelName = channelFor(videoCallRequestId);
        long uid = uidFor(videoCallRequestId, userId);
        int uidInt = (int) uid;
        int ttl = agora.getTokenTtlSeconds();
        Instant expiresAt = Instant.now().plusSeconds(ttl);

        // All joiners publish/subscribe — both parties are full participants,
        // so ROLE_PUBLISHER applies to everyone.
        // tokenExpire/privilegeExpire are seconds-FROM-NOW, not epoch values.
        String token = new RtcTokenBuilder2().buildTokenWithUid(
                agora.getAppId(), agora.getAppCertificate(),
                channelName, uidInt, Role.ROLE_PUBLISHER,
                ttl, ttl);
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "Agora token builder returned an empty token — check "
                            + "video.agora.app-id / app-certificate (32-char hex).");
        }

        return new JoinSession(channelName, token, uid,
                expiresAt.atOffset(ZoneOffset.UTC));
    }

    /**
     * Deterministic per-call-per-user uid in the Agora uint32 space — stable
     * across re-joins so a reconnecting participant keeps their identity.
     */
    static long uidFor(UUID callId, UUID userId) {
        long hash = (callId.getLeastSignificantBits() * 31L)
                + userId.getLeastSignificantBits();
        return Math.floorMod(hash, 0xFFFF_FFFFL);
    }
}
