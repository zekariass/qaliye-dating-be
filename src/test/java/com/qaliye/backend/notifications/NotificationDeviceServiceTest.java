package com.qaliye.backend.notifications;

import com.qaliye.backend.notifications.config.PushProperties;
import com.qaliye.backend.notifications.repository.NotificationDeviceJdbcRepository;
import com.qaliye.backend.notifications.service.NotificationDeviceService;
import com.qaliye.backend.notifications.service.NotificationDeviceService.RegisterRequest;
import com.qaliye.backend.notifications.service.NotificationDeviceService.RegisterResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationDeviceServiceTest {

    @Mock NotificationDeviceJdbcRepository deviceJdbcRepo;

    PushProperties pushProperties = new PushProperties();
    NotificationDeviceService service;

    UUID userId = UUID.randomUUID();
    UUID installationId = UUID.randomUUID();
    String validToken = "ExponentPushToken[xxxxxxxxxxxxxxxxxxxxxx]";

    @BeforeEach
    void setUp() {
        pushProperties.setAppEnvironment("PRODUCTION");
        service = new NotificationDeviceService(deviceJdbcRepo, pushProperties);
    }

    @Test
    void register_validToken_callsUpsert() {
        when(deviceJdbcRepo.lockByToken(validToken)).thenReturn(Optional.empty());

        RegisterResult result = service.register(userId,
                new RegisterRequest(validToken, "IOS", installationId));

        assertThat(result.registered()).isTrue();
        assertThat(result.isActive()).isTrue();
        verify(deviceJdbcRepo).upsert(any(), eq(userId), eq(validToken),
                eq("IOS"), eq(installationId), eq("PRODUCTION"));
    }

    @Test
    void register_validToken_deactivatesOtherInstallationDevices() {
        when(deviceJdbcRepo.lockByToken(validToken)).thenReturn(Optional.empty());

        service.register(userId, new RegisterRequest(validToken, "ANDROID", installationId));

        verify(deviceJdbcRepo).deactivateOtherActiveForInstallation(
                eq(installationId), eq("PRODUCTION"), eq(validToken));
    }

    @Test
    void register_nullInstallationId_noDeactivation() {
        when(deviceJdbcRepo.lockByToken(validToken)).thenReturn(Optional.empty());

        service.register(userId, new RegisterRequest(validToken, "IOS", null));

        verify(deviceJdbcRepo, never()).deactivateOtherActiveForInstallation(any(), any(), any());
    }

    @Test
    void register_realWorldToken_callsUpsert() {
        String realToken = "ExponentPushToken[dKPRciGk3AfiOsUjscC8Xt]";
        when(deviceJdbcRepo.lockByToken(realToken)).thenReturn(Optional.empty());

        RegisterResult result = service.register(userId,
                new RegisterRequest(realToken, "ANDROID", installationId));

        assertThat(result.registered()).isTrue();
        assertThat(result.isActive()).isTrue();
        verify(deviceJdbcRepo).upsert(any(), eq(userId), eq(realToken),
                eq("ANDROID"), eq(installationId), eq("PRODUCTION"));
    }

    @Test
    void register_tokenWithWhitespace_trimsAndAccepts() {
        String paddedToken = "  " + validToken + "  ";
        when(deviceJdbcRepo.lockByToken(paddedToken)).thenReturn(Optional.empty());

        RegisterResult result = service.register(userId,
                new RegisterRequest(paddedToken, "IOS", installationId));

        assertThat(result.registered()).isTrue();
        assertThat(result.isActive()).isTrue();
        verify(deviceJdbcRepo).upsert(any(), eq(userId), eq(paddedToken),
                eq("IOS"), eq(installationId), eq("PRODUCTION"));
    }

    @Test
    void register_invalidToken_throwsIllegalArgument() {
        assertThatThrownBy(() ->
                service.register(userId, new RegisterRequest("invalid-token", "IOS", installationId)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid Expo push token format");
    }

    @Test
    void register_invalidPlatform_throwsIllegalArgument() {
        assertThatThrownBy(() ->
                service.register(userId, new RegisterRequest(validToken, "WEB", installationId)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Platform must be IOS or ANDROID");
    }

    @Test
    void deactivateCurrent_callsDeactivateForMatchingInstallation() {
        var device = new NotificationDeviceJdbcRepository.DeviceRow(
                UUID.randomUUID(), userId, validToken, "IOS", true,
                installationId, "PRODUCTION", null, null, null);
        when(deviceJdbcRepo.findActiveDevicesForUser(userId, "PRODUCTION"))
                .thenReturn(List.of(device));

        service.deactivateCurrent(userId, installationId);

        verify(deviceJdbcRepo).deactivateByTokenAndUser(eq(validToken), eq(userId), eq(installationId));
    }

    @Test
    void deactivateCurrent_noMatchingInstallation_doesNothing() {
        var device = new NotificationDeviceJdbcRepository.DeviceRow(
                UUID.randomUUID(), userId, validToken, "IOS", true,
                UUID.randomUUID(), "PRODUCTION", null, null, null);
        when(deviceJdbcRepo.findActiveDevicesForUser(userId, "PRODUCTION"))
                .thenReturn(List.of(device));

        service.deactivateCurrent(userId, installationId);

        verify(deviceJdbcRepo, never()).deactivateByTokenAndUser(any(), any(), any());
    }

    @Test
    void deactivateAllForUser_delegatesToRepo() {
        service.deactivateAllForUser(userId);
        verify(deviceJdbcRepo).deactivateAllForUser(userId);
    }
}
