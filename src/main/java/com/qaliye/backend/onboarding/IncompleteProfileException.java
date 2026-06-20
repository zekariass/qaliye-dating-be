package com.qaliye.backend.onboarding;

import java.util.List;

public class IncompleteProfileException extends RuntimeException {

    private final List<String> missing;

    public IncompleteProfileException(List<String> missing) {
        super("Incomplete profile: missing required fields");
        this.missing = missing;
    }

    public List<String> getMissing() {
        return missing;
    }
}
