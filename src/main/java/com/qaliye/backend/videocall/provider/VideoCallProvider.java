package com.qaliye.backend.videocall.provider;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * SPI for the video vendor backing accepted video-call requests.
 *
 * The domain model, API and database never see the vendor — only this
 * interface. The active implementation is selected by {@code video.platform}
 * (matched against {@link #name()}); see {@link VideoConfig}.
 */
public interface VideoCallProvider {

    /** Config key this provider answers to — e.g. {@code "AGORA"}. */
    String name();

    /** Opaque channel/room name persisted on the request at accept time. */
    String channelFor(UUID videoCallRequestId);

    /**
     * Mints join credentials on demand for a participant. Tokens are never
     * persisted — a fresh one is issued on every call.
     *
     * @param host      true when the joiner facilitates the call (unused
     *                  today — both parties join as regular participants)
     * @param notAfter  upper bound for the token's privilege expiry —
     *                  typically the per-call deadline. The token lives at
     *                  most {@code notAfter}, and no longer than the
     *                  provider's configured max TTL. {@code null} =
     *                  uncapped (max TTL applies).
     */
    JoinSession issueJoin(UUID videoCallRequestId, UUID userId, boolean host,
                          OffsetDateTime notAfter);

    /** Everything a client needs to join a call. */
    record JoinSession(
            String channelName,
            String token,
            long uid,
            OffsetDateTime expiresAt) {}
}
