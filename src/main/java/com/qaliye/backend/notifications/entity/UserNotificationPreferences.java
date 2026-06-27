package com.qaliye.backend.notifications.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "user_notification_preferences")
@Getter
@Setter
@NoArgsConstructor
public class UserNotificationPreferences {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "push_enabled", nullable = false)
    private boolean pushEnabled = true;

    @Column(name = "message_notifications_enabled", nullable = false)
    private boolean messageNotificationsEnabled = true;

    @Column(name = "match_notifications_enabled", nullable = false)
    private boolean matchNotificationsEnabled = true;

    @Column(name = "like_notifications_enabled", nullable = false)
    private boolean likeNotificationsEnabled = true;

    @Column(name = "message_preview_enabled", nullable = false)
    private boolean messagePreviewEnabled = false;

    @Column(name = "marketing_notifications_enabled", nullable = false)
    private boolean marketingNotificationsEnabled = false;

    @Column(name = "marketing_notifications_opted_in_at")
    private OffsetDateTime marketingNotificationsOptedInAt;

    @Column(name = "marketing_notifications_consent_version")
    private String marketingNotificationsConsentVersion;

    @Column(name = "last_marketing_sent_at")
    private OffsetDateTime lastMarketingSentAt;

    @Column(name = "marketing_reservation_event_id")
    private UUID marketingReservationEventId;

    @Column(name = "marketing_reservation_expires_at")
    private OffsetDateTime marketingReservationExpiresAt;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
