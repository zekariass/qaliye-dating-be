package com.qaliye.backend.notifications.service;

import com.qaliye.backend.notifications.entity.UserNotificationPreferences;
import com.qaliye.backend.notifications.repository.NotificationPreferencesRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class NotificationPreferencesService {

    private final NotificationPreferencesRepository prefsRepo;

    public NotificationPreferencesService(NotificationPreferencesRepository prefsRepo) {
        this.prefsRepo = prefsRepo;
    }

    public record UpdateRequest(
            Boolean pushEnabled,
            Boolean messageNotificationsEnabled,
            Boolean matchNotificationsEnabled,
            Boolean likeNotificationsEnabled,
            Boolean messagePreviewEnabled,
            Boolean marketingNotificationsEnabled,
            String marketingNotificationsConsentVersion
    ) {}

    @Transactional(readOnly = true)
    public UserNotificationPreferences getPreferences(UUID userId) {
        return prefsRepo.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "No notification preferences found for user " + userId));
    }

    @Transactional
    public UserNotificationPreferences updatePreferences(UUID userId, UpdateRequest req) {
        UserNotificationPreferences prefs = prefsRepo.findByUserId(userId)
                .orElseGet(() -> {
                    UserNotificationPreferences p = new UserNotificationPreferences();
                    p.setUserId(userId);
                    p.setCreatedAt(OffsetDateTime.now());
                    return p;
                });

        if (req.pushEnabled() != null) {
            prefs.setPushEnabled(req.pushEnabled());
        }
        if (req.messageNotificationsEnabled() != null) {
            prefs.setMessageNotificationsEnabled(req.messageNotificationsEnabled());
        }
        if (req.matchNotificationsEnabled() != null) {
            prefs.setMatchNotificationsEnabled(req.matchNotificationsEnabled());
        }
        if (req.likeNotificationsEnabled() != null) {
            prefs.setLikeNotificationsEnabled(req.likeNotificationsEnabled());
        }
        if (req.messagePreviewEnabled() != null) {
            prefs.setMessagePreviewEnabled(req.messagePreviewEnabled());
        }

        applyMarketingChange(prefs, req);

        prefs.setUpdatedAt(OffsetDateTime.now());
        return prefsRepo.save(prefs);
    }

    private void applyMarketingChange(UserNotificationPreferences prefs, UpdateRequest req) {
        if (req.marketingNotificationsEnabled() == null) {
            return;
        }

        boolean wantsMarketing = req.marketingNotificationsEnabled();
        boolean currentlyEnabled = prefs.isMarketingNotificationsEnabled();

        if (wantsMarketing && !currentlyEnabled) {
            String consentVersion = req.marketingNotificationsConsentVersion();
            if (consentVersion == null || consentVersion.isBlank()) {
                throw new IllegalArgumentException(
                        "marketingNotificationsConsentVersion is required to enable marketing notifications.");
            }
            prefs.setMarketingNotificationsEnabled(true);
            prefs.setMarketingNotificationsOptedInAt(OffsetDateTime.now());
            prefs.setMarketingNotificationsConsentVersion(consentVersion.trim());
        } else if (!wantsMarketing && currentlyEnabled) {
            prefs.setMarketingNotificationsEnabled(false);
        }
    }

    @Transactional
    public void ensurePreferencesExist(UUID userId) {
        prefsRepo.insertIfAbsent(userId);
    }
}
