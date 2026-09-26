package com.qaliye.backend.blinddate;

/**
 * Status values, decision values and feature-action codes used by the Blind Date
 * feature. Kept as constants (rather than enums) so they match the VARCHAR CHECK
 * constraints in V67 exactly and can be passed straight into SQL parameters.
 */
public final class BlindDateConstants {

    private BlindDateConstants() {
    }

    // ── Feature action codes ────────────────────────────────────────────────
    public static final String ACTION_SESSION_CREATE = "BLIND_DATE_SESSION_CREATE";
    public static final String ACTION_PARTICIPATE = "BLIND_DATE_PARTICIPATE";

    // ── Session status ─────────────────────────────────────────────────────
    public static final String SESSION_OPEN = "OPEN";
    public static final String SESSION_REVEAL = "REVEAL";
    public static final String SESSION_CLOSED = "CLOSED";
    public static final String SESSION_EXPIRED = "EXPIRED";
    public static final String SESSION_COMPLETED = "COMPLETED";
    public static final String SESSION_CANCELLED = "CANCELLED";

    // ── Round status ───────────────────────────────────────────────────────
    public static final String ROUND_OPEN = "OPEN";
    public static final String ROUND_CLOSED = "CLOSED";

    // ── Participant status ─────────────────────────────────────────────────
    public static final String PARTICIPANT_ACTIVE = "ACTIVE";
    public static final String PARTICIPANT_ADVANCED = "ADVANCED";
    public static final String PARTICIPANT_ELIMINATED = "ELIMINATED";
    public static final String PARTICIPANT_FINALIST = "FINALIST";
    public static final String PARTICIPANT_REVEALED = "REVEALED";
    public static final String PARTICIPANT_WITHDRAWN = "WITHDRAWN";

    // ── Round selection decisions ──────────────────────────────────────────
    public static final String DECISION_ADVANCE = "ADVANCE";
    public static final String DECISION_ELIMINATE = "ELIMINATE";
    public static final String DECISION_SELECT_FINALIST = "SELECT_FINALIST";

    // ── Final decisions ────────────────────────────────────────────────────
    public static final String FINAL_PENDING = "PENDING";
    public static final String FINAL_INTERESTED = "INTERESTED";
    public static final String FINAL_NOT_INTERESTED = "NOT_INTERESTED";

    // ── Final outcomes ─────────────────────────────────────────────────────
    public static final String OUTCOME_MATCHED = "MATCHED";
    public static final String OUTCOME_NO_MATCH = "NO_MATCH";
    public static final String OUTCOME_ALREADY_MATCHED = "ALREADY_MATCHED";
    public static final String OUTCOME_EXPIRED = "EXPIRED";

    // ── Discovery action integration ───────────────────────────────────────
    /** Marker on user_discovery_actions so blind-date likes are excluded from rewind. */
    public static final String ACTION_SOURCE_BLIND_DATE = "BLIND_DATE";
    /** Dedicated inactive LIKE variant used for system-generated blind date likes. */
    public static final String LIKE_VARIANT_BLIND_DATE = "BLIND_DATE";

    // ── Notification alert codes (ride on the generic ACCOUNT_ALERT type) ───
    public static final String ALERT_REVEAL = "BLIND_DATE_REVEAL";
    public static final String ALERT_ELIMINATED = "BLIND_DATE_ELIMINATED";
    public static final String ALERT_MATCHED = "BLIND_DATE_MATCHED";
    public static final String ALERT_NO_MATCH = "BLIND_DATE_NO_MATCH";

    // ── Limits ─────────────────────────────────────────────────────────────
    public static final int MAX_CUSTOM_QUESTION_LENGTH = 500;
    public static final int MAX_ANSWER_LENGTH = 2000;
    public static final int MIN_ROUND_QUESTIONS = 1;
    public static final int MAX_ROUND_QUESTIONS = 20;
}
