package com.qaliye.backend.notifications.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "notification_devices")
@Getter
@Setter
@NoArgsConstructor
public class NotificationDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "device_token", nullable = false, unique = true)
    private String deviceToken;

    @Column(nullable = false)
    private String platform;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @Column(name = "installation_id")
    private UUID installationId;

    @Column(name = "app_environment", nullable = false)
    private String appEnvironment = "PRODUCTION";

    @Column(name = "disabled_at")
    private OffsetDateTime disabledAt;

    @Column(name = "last_error_code")
    private String lastErrorCode;

    @Column(name = "last_error_at")
    private OffsetDateTime lastErrorAt;

    @Column(name = "last_seen_at")
    private OffsetDateTime lastSeenAt;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
