package com.qaliye.backend.notifications;

import com.qaliye.backend.notifications.entity.UserNotificationPreferences;
import com.qaliye.backend.notifications.repository.NotificationPreferencesRepository;
import com.qaliye.backend.notifications.service.NotificationPreferencesService;
import com.qaliye.backend.notifications.service.NotificationPreferencesService.UpdateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationPreferencesServiceTest {

    @Mock NotificationPreferencesRepository prefsRepo;

    NotificationPreferencesService service;
    UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new NotificationPreferencesService(prefsRepo);
    }

    @Test
    void getPreferences_existingUser_returnsPrefs() {
        UserNotificationPreferences prefs = defaultPrefs();
        when(prefsRepo.findByUserId(userId)).thenReturn(Optional.of(prefs));

        UserNotificationPreferences result = service.getPreferences(userId);

        assertThat(result).isSameAs(prefs);
    }

    @Test
    void getPreferences_missingPrefs_throwsIllegalState() {
        when(prefsRepo.findByUserId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPreferences(userId))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void updatePreferences_enableMarketing_withConsentVersion_succeeds() {
        UserNotificationPreferences prefs = defaultPrefs();
        when(prefsRepo.findByUserId(userId)).thenReturn(Optional.of(prefs));
        when(prefsRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateRequest req = new UpdateRequest(null, null, null, null, null,
                true, "v1.0");
        UserNotificationPreferences updated = service.updatePreferences(userId, req);

        assertThat(updated.isMarketingNotificationsEnabled()).isTrue();
        assertThat(updated.getMarketingNotificationsConsentVersion()).isEqualTo("v1.0");
        assertThat(updated.getMarketingNotificationsOptedInAt()).isNotNull();
    }

    @Test
    void updatePreferences_enableMarketing_withoutConsentVersion_throwsIllegalArgument() {
        UserNotificationPreferences prefs = defaultPrefs();
        when(prefsRepo.findByUserId(userId)).thenReturn(Optional.of(prefs));

        UpdateRequest req = new UpdateRequest(null, null, null, null, null,
                true, null);

        assertThatThrownBy(() -> service.updatePreferences(userId, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("consentVersion");
    }

    @Test
    void updatePreferences_enableMarketing_withBlankConsentVersion_throwsIllegalArgument() {
        UserNotificationPreferences prefs = defaultPrefs();
        when(prefsRepo.findByUserId(userId)).thenReturn(Optional.of(prefs));

        UpdateRequest req = new UpdateRequest(null, null, null, null, null,
                true, "   ");

        assertThatThrownBy(() -> service.updatePreferences(userId, req))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updatePreferences_disableMarketing_clearsFlag() {
        UserNotificationPreferences prefs = defaultPrefs();
        prefs.setMarketingNotificationsEnabled(true);
        prefs.setMarketingNotificationsConsentVersion("v1.0");
        prefs.setMarketingNotificationsOptedInAt(OffsetDateTime.now());
        when(prefsRepo.findByUserId(userId)).thenReturn(Optional.of(prefs));
        when(prefsRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateRequest req = new UpdateRequest(null, null, null, null, null,
                false, null);
        UserNotificationPreferences updated = service.updatePreferences(userId, req);

        assertThat(updated.isMarketingNotificationsEnabled()).isFalse();
    }

    @Test
    void updatePreferences_pushEnabled_false_updatesPushEnabled() {
        UserNotificationPreferences prefs = defaultPrefs();
        when(prefsRepo.findByUserId(userId)).thenReturn(Optional.of(prefs));
        when(prefsRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateRequest req = new UpdateRequest(false, null, null, null, null, null, null);
        UserNotificationPreferences updated = service.updatePreferences(userId, req);

        assertThat(updated.isPushEnabled()).isFalse();
    }

    @Test
    void updatePreferences_nullFields_noChange() {
        UserNotificationPreferences prefs = defaultPrefs();
        when(prefsRepo.findByUserId(userId)).thenReturn(Optional.of(prefs));
        when(prefsRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateRequest req = new UpdateRequest(null, null, null, null, null, null, null);
        UserNotificationPreferences updated = service.updatePreferences(userId, req);

        assertThat(updated.isPushEnabled()).isTrue();
        assertThat(updated.isMarketingNotificationsEnabled()).isFalse();
    }

    private UserNotificationPreferences defaultPrefs() {
        UserNotificationPreferences p = new UserNotificationPreferences();
        p.setUserId(userId);
        p.setPushEnabled(true);
        p.setMessageNotificationsEnabled(true);
        p.setMatchNotificationsEnabled(true);
        p.setLikeNotificationsEnabled(true);
        p.setMessagePreviewEnabled(false);
        p.setMarketingNotificationsEnabled(false);
        p.setCreatedAt(OffsetDateTime.now());
        p.setUpdatedAt(OffsetDateTime.now());
        return p;
    }
}
