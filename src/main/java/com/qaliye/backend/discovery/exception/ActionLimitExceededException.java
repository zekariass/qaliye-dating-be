package com.qaliye.backend.discovery.exception;

public class ActionLimitExceededException extends DiscoveryException {

    private final String actionType;
    private final String periodType;

    public ActionLimitExceededException(String actionType, String periodType) {
        super("LIMIT_EXCEEDED", toMessage(actionType, periodType), 429);
        this.actionType = actionType;
        this.periodType = periodType;
    }

    public String getActionType() { return actionType; }
    public String getPeriodType() { return periodType; }

    private static String toMessage(String actionType, String periodType) {
        String period = switch (periodType != null ? periodType : "DAILY") {
            case "MONTHLY", "SUBSCRIPTION_MONTH" -> "monthly";
            case "BILLING_CYCLE" -> "billing cycle";
            default -> "daily";
        };
        return switch (actionType) {
            case "LIKES" -> "You have reached your " + period + " like limit.";
            case "SUPERLIKES", "SUPER_LIKE" -> "You have reached your " + period + " super like limit.";
            case "REWINDS", "REWIND" -> "You have reached your " + period + " rewind limit.";
            case "BOOST", "BOOSTS" -> "You have reached your " + period + " boost limit.";
            case "VOICE_CHAT_MSGS", "VOICE_MESSAGE" -> "You have reached your " + period + " voice message limit.";
            case "IMAGE_CHAT_MSGS", "IMAGE_MESSAGE" -> "You have reached your " + period + " image message limit.";
            case "RETURN_PASSED_PROFILE" -> "You have reached your " + period + " limit for returning passed profiles.";
            case "SEE_WHO_LIKED_YOU" -> "You have reached your " + period + " limit for seeing who liked you.";
            case "SUPER_MESSAGE" -> "You have reached your " + period + " super message limit.";
            default -> "You have reached your " + period + " limit for this action.";
        };
    }
}
