package com.qaliye.backend.notifications.service;

import com.qaliye.backend.notifications.config.PushProperties;
import com.qaliye.backend.notifications.repository.NotificationDeviceJdbcRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class NotificationDeviceService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeviceService.class);

    private static final Pattern EXPO_TOKEN_PATTERN =
            Pattern.compile("^ExponentPushToken\\[.{1,200}\\]$");

    private static final Pattern EXPO_EA_TOKEN_PATTERN =
            Pattern.compile("^[a-zA-Z0-9_\\-]{20,200}$");

    private final NotificationDeviceJdbcRepository deviceJdbcRepo;
    private final PushProperties pushProperties;

    public NotificationDeviceService(NotificationDeviceJdbcRepository deviceJdbcRepo,
                                     PushProperties pushProperties) {
        this.deviceJdbcRepo = deviceJdbcRepo;
        this.pushProperties = pushProperties;
    }

    public record RegisterRequest(String expoPushToken, String platform, UUID installationId) {}
    public record RegisterResult(boolean registered, boolean isActive) {}

    @Transactional
    public RegisterResult register(UUID userId, RegisterRequest request) {
        validateToken(request.expoPushToken());
        validatePlatform(request.platform());

        String appEnvironment = pushProperties.getAppEnvironment();

        deviceJdbcRepo.lockByToken(request.expoPushToken());

        deviceJdbcRepo.upsert(
                UUID.randomUUID(),
                userId,
                request.expoPushToken(),
                request.platform(),
                request.installationId(),
                appEnvironment
        );

        if (request.installationId() != null) {
            deviceJdbcRepo.deactivateOtherActiveForInstallation(
                    request.installationId(),
                    appEnvironment,
                    request.expoPushToken()
            );
        }

        return new RegisterResult(true, true);
    }

    @Transactional
    public void deactivateCurrent(UUID userId, UUID installationId) {
        deviceJdbcRepo.findActiveDevicesForUser(userId, pushProperties.getAppEnvironment())
                .stream()
                .filter(d -> installationId.equals(d.installationId()))
                .forEach(d -> {
                    deviceJdbcRepo.lockByToken(d.deviceToken());
                    deviceJdbcRepo.deactivateByTokenAndUser(
                            d.deviceToken(), userId, installationId);
                });
    }

    @Transactional
    public void deactivateAllForUser(UUID userId) {
        deviceJdbcRepo.deactivateAllForUser(userId);
    }

    private void validateToken(String token) {
        String trimmed = token != null ? token.trim() : null;
        if (trimmed == null || trimmed.isEmpty()
                || (!EXPO_TOKEN_PATTERN.matcher(trimmed).matches()
                    && !EXPO_EA_TOKEN_PATTERN.matcher(trimmed).matches())) {
            log.warn("Invalid Expo push token format. Received: '{}'", token);
            throw new IllegalArgumentException("Invalid Expo push token format.");
        }
    }

    private void validatePlatform(String platform) {
        if (!"IOS".equals(platform) && !"ANDROID".equals(platform)) {
            throw new IllegalArgumentException("Platform must be IOS or ANDROID.");
        }
    }
}
