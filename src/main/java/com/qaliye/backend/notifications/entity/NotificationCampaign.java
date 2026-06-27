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
@Table(name = "notification_campaigns")
@Getter
@Setter
@NoArgsConstructor
public class NotificationCampaign {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "campaign_key", nullable = false, unique = true)
    private String campaignKey;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String body;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "navigation_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> navigationPayload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "audience_definition", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> audienceDefinition;

    @Column(nullable = false)
    private String status = "DRAFT";

    @Column(name = "scheduled_at")
    private OffsetDateTime scheduledAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
