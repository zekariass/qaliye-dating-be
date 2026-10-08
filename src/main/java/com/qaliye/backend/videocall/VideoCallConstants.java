package com.qaliye.backend.videocall;

/**
 * Status values, alert codes and the feature-action code for the
 * video-call-request feature (post-match, match-scoped calls).
 *
 * <p>Kept in sync with the CHECK constraints in V71.
 */
public final class VideoCallConstants {

    private VideoCallConstants() {
    }

    // ── Feature action codes ────────────────────────────────────────────
    public static final String ACTION_VIDEO_CALL = "VIDEO_CALL";
    public static final String ACTION_AUDIO_CALL = "AUDIO_CALL";

    // ── video_call_requests.call_type ─────────────────────────────────────
    public static final String CALL_TYPE_VIDEO = "VIDEO";
    public static final String CALL_TYPE_AUDIO = "AUDIO";

    // ── video_call_requests.status ──────────────────────────────────────
    public static final String STATUS_PENDING   = "PENDING";
    public static final String STATUS_ACCEPTED  = "ACCEPTED";
    public static final String STATUS_DECLINED  = "DECLINED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_EXPIRED   = "EXPIRED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    /**
     * ACCEPTED but one participant never joined within the join-grace
     * window — auto-closed by the sweeper. Terminal; frees the match's
     * live-request slot like the other end states.
     */
    public static final String STATUS_NO_SHOW   = "NO_SHOW";

    // ── Notification alert codes ─────────────────────────────────────────
    // Selected per video_call_requests.call_type — AUDIO_CALL_* variants
    // mirror the VIDEO_CALL_* lifecycle events for audio requests.
    public static final String ALERT_REQUESTED = "VIDEO_CALL_REQUESTED";
    public static final String ALERT_ACCEPTED  = "VIDEO_CALL_ACCEPTED";
    public static final String ALERT_DECLINED  = "VIDEO_CALL_DECLINED";
    public static final String ALERT_CANCELLED = "VIDEO_CALL_CANCELLED";
    public static final String ALERT_EXPIRED   = "VIDEO_CALL_EXPIRED";

    public static final String ALERT_AUDIO_REQUESTED = "AUDIO_CALL_REQUESTED";
    public static final String ALERT_AUDIO_ACCEPTED  = "AUDIO_CALL_ACCEPTED";
    public static final String ALERT_AUDIO_DECLINED  = "AUDIO_CALL_DECLINED";
    public static final String ALERT_AUDIO_CANCELLED = "AUDIO_CALL_CANCELLED";
    public static final String ALERT_AUDIO_EXPIRED   = "AUDIO_CALL_EXPIRED";

    /**
     * Call hit its per-type duration cap (call_deadline_at elapsed) — the
     * deadline sweeper / post-deadline join completes the request and both
     * parties get this so the client can render "time limit reached"
     * instead of a generic ended screen.
     */
    public static final String ALERT_ENDED_TIME_LIMIT       = "VIDEO_CALL_ENDED_TIME_LIMIT";
    public static final String ALERT_AUDIO_ENDED_TIME_LIMIT = "AUDIO_CALL_ENDED_TIME_LIMIT";

    /**
     * One participant never joined within the join-grace window — sent to
     * BOTH parties: the waiter learns why the call closed, the absent party
     * learns they missed it.
     */
    public static final String ALERT_NO_SHOW       = "VIDEO_CALL_NO_SHOW";
    public static final String ALERT_AUDIO_NO_SHOW = "AUDIO_CALL_NO_SHOW";

    /** Picks the VIDEO_CALL_* or AUDIO_CALL_* alert code by call_type. */
    public static String alertCode(String videoCode, String audioCode, String callType) {
        return CALL_TYPE_AUDIO.equals(callType) ? audioCode : videoCode;
    }
}
