package com.qaliye.backend.notifications.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "notification_outbox_events")
@Getter
@Setter
@NoArgsConstructor
public class NotificationOutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "notification_type", nullable = false)
    private String notificationType;

    @Column(name = "recipient_user_id", nullable = false)
    private UUID recipientUserId;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "match_id")
    private UUID matchId;

    @Column(name = "message_id")
    private UUID messageId;

    @Column(name = "discovery_action_id")
    private UUID discoveryActionId;

    @Column(name = "campaign_id")
    private UUID campaignId;

    @Column(name = "dedupe_key", nullable = false, unique = true)
    private String dedupeKey;

    @Column(name = "collapse_key")
    private String collapseKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(nullable = false)
    private String status = "PENDING";

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "available_at", nullable = false)
    private OffsetDateTime availableAt;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "locked_at")
    private OffsetDateTime lockedAt;

    @Column(name = "locked_by")
    private String lockedBy;

    @Column(name = "lease_expires_at")
    private OffsetDateTime leaseExpiresAt;

    @Column(name = "fanout_completed_at")
    private OffsetDateTime fanoutCompletedAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;
}
