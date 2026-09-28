package com.qaliye.backend.blinddate.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Structural caps for Blind Date sessions. These bound session size and length
 * for every user equally — they are not per-plan monetization limits (those
 * live in feature_actions / plan rules).
 */
@Component
@ConfigurationProperties(prefix = "blind-date")
public class BlindDateProperties {

    /** Maximum number of still-active participants a session may hold. */
    private int maxParticipants = 20;

    /** Maximum number of rounds a session may run before a finalist must be chosen. */
    private int maxRounds = 5;

    /** Maximum number of questions a creator may snapshot into a single round. */
    private int maxRoundQuestions = 20;

    /** Maximum number of active platform questions a user may keep in their question set. */
    private int maxQuestions = 50;

    /** Maximum number of active custom questions a user may keep in their question set. */
    private int maxCustomQuestions = 10;

    /** Hours both sides have to submit their final decision after a finalist is revealed. */
    private long decisionWindowHours = 72;

    public int getMaxParticipants() {
        return maxParticipants;
    }

    public void setMaxParticipants(int maxParticipants) {
        this.maxParticipants = maxParticipants;
    }

    public int getMaxRounds() {
        return maxRounds;
    }

    public void setMaxRounds(int maxRounds) {
        this.maxRounds = maxRounds;
    }

    public int getMaxRoundQuestions() {
        return maxRoundQuestions;
    }

    public void setMaxRoundQuestions(int maxRoundQuestions) {
        this.maxRoundQuestions = maxRoundQuestions;
    }

    public int getMaxQuestions() {
        return maxQuestions;
    }

    public void setMaxQuestions(int maxQuestions) {
        this.maxQuestions = maxQuestions;
    }

    public int getMaxCustomQuestions() {
        return maxCustomQuestions;
    }

    public void setMaxCustomQuestions(int maxCustomQuestions) {
        this.maxCustomQuestions = maxCustomQuestions;
    }

    public long getDecisionWindowHours() {
        return decisionWindowHours;
    }

    public void setDecisionWindowHours(long decisionWindowHours) {
        this.decisionWindowHours = decisionWindowHours;
    }
}
