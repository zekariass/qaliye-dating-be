package com.qaliye.backend.actions;

public record TierLimits(int likesPerDay, int superLikesPerDay, int rewindsPerDay) {

    public static TierLimits free() {
        return new TierLimits(50, 1, 1);
    }
}
