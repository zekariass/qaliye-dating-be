package com.qaliye.backend.matchmaking;


/**
 * Status values, decision values, and feature-action codes used by the
 * Personal Matchmaking feature. Kept as constants so they match the VARCHAR
 * CHECK constraints in V70/V71 exactly and can be passed straight into SQL parameters.
 */
public final class MatchmakingConstants {

    private MatchmakingConstants() {
    }

    // ── Feature action codes ────────────────────────────────────────────────
    public static final String ACTION_REQUEST    = "PERSONAL_MATCHMAKING_REQUEST";

    // ── Request status ─────────────────────────────────────────────────────
    public static final String REQUEST_OPEN      = "OPEN";
    public static final String REQUEST_ON_HOLD   = "ON_HOLD";
    public static final String REQUEST_MATCHED   = "MATCHED";
    public static final String REQUEST_COMPLETED = "COMPLETED";
    public static final String REQUEST_CANCELLED = "CANCELLED";
    public static final String REQUEST_EXPIRED   = "EXPIRED";

    // ── Introduction status (MUTUAL_INTEREST removed in V71) ───────────────
    public static final String INTRO_PROPOSED  = "PROPOSED";
    public static final String INTRO_DECLINED  = "DECLINED";
    public static final String INTRO_MATCHED   = "MATCHED";
    public static final String INTRO_CANCELLED = "CANCELLED";
    public static final String INTRO_EXPIRED   = "EXPIRED";

    // ── Introduction decisions ─────────────────────────────────────────────
    public static final String DECISION_PENDING        = "PENDING";
    public static final String DECISION_INTERESTED     = "INTERESTED";
    public static final String DECISION_NOT_INTERESTED = "NOT_INTERESTED";

    // ── Notification alert codes — matchmaking ────────────────────────────
    public static final String ALERT_REQUEST_CREATED         = "MATCHMAKING_REQUEST_CREATED";
    public static final String ALERT_REQUEST_CANCELLED       = "MATCHMAKING_REQUEST_CANCELLED";
    public static final String ALERT_REQUEST_EXPIRED         = "MATCHMAKING_REQUEST_EXPIRED";
    public static final String ALERT_INTRODUCTION_PROPOSED   = "MATCHMAKING_INTRODUCTION_PROPOSED";
    public static final String ALERT_INTRODUCTION_DECLINED   = "MATCHMAKING_INTRODUCTION_DECLINED";
    public static final String ALERT_INTRODUCTION_CANCELLED  = "MATCHMAKING_INTRODUCTION_CANCELLED";
    public static final String ALERT_INTRODUCTION_EXPIRED    = "MATCHMAKING_INTRODUCTION_EXPIRED";
    public static final String ALERT_MATCHED                 = "MATCHMAKING_MATCHED";

    // ── match_source value ────────────────────────────────────────────────
    public static final String MATCH_SOURCE = "MATCHMAKING";
}
