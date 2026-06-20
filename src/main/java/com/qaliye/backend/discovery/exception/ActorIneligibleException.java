package com.qaliye.backend.discovery.exception;

public class ActorIneligibleException extends DiscoveryException {

    public ActorIneligibleException(String code, String message) {
        super(code, message, 403);
    }

    public static ActorIneligibleException accountIneligible() {
        return new ActorIneligibleException("DISCOVERY_ACTOR_INELIGIBLE",
                "Your account is not eligible to use discovery.");
    }

    public static ActorIneligibleException profileIncomplete() {
        return new ActorIneligibleException("ACTOR_PROFILE_INCOMPLETE",
                "Please complete your profile and set discovery preferences before swiping.");
    }
}
