package com.qaliye.backend.notifications.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "notification_deliveries")
@Getter
@Setter
@NoArgsConstructor
public class NotificationDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "notification_outbox_event_id", nullable = false, updatable = false)
    private UUID notificationOutboxEventId;

    @Column(name = "notification_device_id", nullable = false, updatable = false)
    private UUID notificationDeviceId;

    @Column(nullable = false)
    private String status = "PENDING";

    @Column(name = "resolution_code")
    private String resolutionCode;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "available_at", nullable = false)
    private OffsetDateTime availableAt;

    @Column(name = "locked_at")
    private OffsetDateTime lockedAt;

    @Column(name = "locked_by")
    private String lockedBy;

    @Column(name = "lease_expires_at")
    private OffsetDateTime leaseExpiresAt;

    @Column(name = "provider_ticket_id")
    private String providerTicketId;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    @Column(name = "next_receipt_check_at")
    private OffsetDateTime nextReceiptCheckAt;

    @Column(name = "receipt_deadline_at")
    private OffsetDateTime receiptDeadlineAt;

    @Column(name = "receipt_checked_at")
    private OffsetDateTime receiptCheckedAt;

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    @Column(name = "last_error_code")
    private String lastErrorCode;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
