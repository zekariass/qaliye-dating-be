package com.qaliye.backend.discovery.exception;

public class InvalidLikeVariantException extends DiscoveryException {

    public InvalidLikeVariantException(String message) {
        super("INVALID_LIKE_VARIANT", message, 400);
    }

    public static InvalidLikeVariantException missing() {
        return new InvalidLikeVariantException("actionVariantCode is required for LIKE actions.");
    }

    public static InvalidLikeVariantException unknown(String variantCode) {
        return new InvalidLikeVariantException("Unknown LIKE variant: " + variantCode);
    }

    public static InvalidLikeVariantException inactive(String variantCode) {
        return new InvalidLikeVariantException("LIKE variant is not currently active: " + variantCode);
    }
}
