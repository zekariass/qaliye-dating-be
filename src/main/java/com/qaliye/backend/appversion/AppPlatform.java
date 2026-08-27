package com.qaliye.backend.appversion;

public enum AppPlatform {
    ANDROID, IOS;

    public static AppPlatform fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Platform parameter is required");
        }
        return switch (value.trim().toUpperCase()) {
            case "ANDROID" -> ANDROID;
            case "IOS" -> IOS;
            default -> throw new IllegalArgumentException("Unsupported platform: '" + value + "'. Supported values: android, ios");
        };
    }

    @Override
    public String toString() {
        return name().toLowerCase();
    }
}
