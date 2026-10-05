package com.qaliye.backend.matchmaking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Platform-level tunables for the Personal Matchmaking feature.
 * All values can be overridden via environment variables.
 */
@ConfigurationProperties(prefix = "matchmaking")
public class MatchmakingProperties {

    /** How long a new matchmaking request stays active before auto-expiring (days). */
    private int requestLifetimeDays = 90;

    /** How long users have to respond to an introduction proposal (hours). */
    private int introductionDecisionWindowHours = 72;

    /**
     * Minimum days that must pass before the same two requests can be introduced again.
     * Set to 0 to disable the cooldown.
     */
    private int reintroductionCooldownDays = 30;

    /** Maximum number of candidates returned per candidate search. */
    private int candidateSearchCap = 200;

    /**
     * Maximum number of introductions a single request may consume (DECLINED / MATCHED /
     * EXPIRED count; CANCELLED never counts). When the quota is reached the request
     * transitions to COMPLETED instead of reopening.
     */
    private int introductionsPerRequest = 1;

    /** How long a PENDING video-call request stays open before the sweeper expires it (hours). */
    private int videoCallRequestExpiryHours = 48;

    /** Minimum seconds between manual reminder pushes on a PENDING call request. */
    private int videoCallReminderCooldownSeconds = 120;

    public int getRequestLifetimeDays() { return requestLifetimeDays; }
    public void setRequestLifetimeDays(int requestLifetimeDays) { this.requestLifetimeDays = requestLifetimeDays; }

    public int getIntroductionDecisionWindowHours() { return introductionDecisionWindowHours; }
    public void setIntroductionDecisionWindowHours(int introductionDecisionWindowHours) { this.introductionDecisionWindowHours = introductionDecisionWindowHours; }

    public int getReintroductionCooldownDays() { return reintroductionCooldownDays; }
    public void setReintroductionCooldownDays(int reintroductionCooldownDays) { this.reintroductionCooldownDays = reintroductionCooldownDays; }

    public int getCandidateSearchCap() { return candidateSearchCap; }
    public void setCandidateSearchCap(int candidateSearchCap) { this.candidateSearchCap = candidateSearchCap; }

    public int getIntroductionsPerRequest() { return introductionsPerRequest; }
    public void setIntroductionsPerRequest(int introductionsPerRequest) { this.introductionsPerRequest = introductionsPerRequest; }

    public int getVideoCallRequestExpiryHours() { return videoCallRequestExpiryHours; }
    public void setVideoCallRequestExpiryHours(int videoCallRequestExpiryHours) { this.videoCallRequestExpiryHours = videoCallRequestExpiryHours; }

    public int getVideoCallReminderCooldownSeconds() { return videoCallReminderCooldownSeconds; }
    public void setVideoCallReminderCooldownSeconds(int v) { this.videoCallReminderCooldownSeconds = v; }
}
