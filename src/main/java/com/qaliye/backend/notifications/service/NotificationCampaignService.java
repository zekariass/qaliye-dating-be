package com.qaliye.backend.notifications.service;

import com.qaliye.backend.notifications.entity.NotificationCampaign;
import com.qaliye.backend.notifications.repository.NotificationCampaignRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class NotificationCampaignService {

    private final NotificationCampaignRepository campaignRepo;

    public NotificationCampaignService(NotificationCampaignRepository campaignRepo) {
        this.campaignRepo = campaignRepo;
    }

    public record CreateRequest(
            String campaignKey,
            String title,
            String body,
            Map<String, Object> navigationPayload,
            Map<String, Object> audienceDefinition
    ) {}

    public record UpdateRequest(
            String title,
            String body,
            Map<String, Object> navigationPayload,
            Map<String, Object> audienceDefinition,
            String status,
            OffsetDateTime scheduledAt
    ) {}

    @Transactional(readOnly = true)
    public Page<NotificationCampaign> listCampaigns(String status, Pageable pageable) {
        if (status != null && !status.isBlank()) {
            return campaignRepo.findByStatus(status, pageable);
        }
        return campaignRepo.findAllByOrderByCreatedAtDesc(pageable);
    }

    @Transactional(readOnly = true)
    public NotificationCampaign getCampaign(UUID id) {
        return campaignRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found: " + id));
    }

    @Transactional
    public NotificationCampaign createCampaign(UUID createdByUserId, CreateRequest req) {
        validateCampaignContent(req.title(), req.body(), req.campaignKey());

        NotificationCampaign c = new NotificationCampaign();
        c.setCampaignKey(req.campaignKey().trim());
        c.setTitle(req.title().trim());
        c.setBody(req.body().trim());
        c.setNavigationPayload(req.navigationPayload() != null ? req.navigationPayload() : Map.of());
        c.setAudienceDefinition(req.audienceDefinition() != null ? req.audienceDefinition() : Map.of());
        c.setStatus("DRAFT");
        c.setCreatedByUserId(createdByUserId);
        c.setCreatedAt(OffsetDateTime.now());
        c.setUpdatedAt(OffsetDateTime.now());
        return campaignRepo.save(c);
    }

    @Transactional
    public NotificationCampaign updateCampaign(UUID id, UpdateRequest req) {
        NotificationCampaign c = campaignRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found: " + id));

        String currentStatus = c.getStatus();
        if (!"DRAFT".equals(currentStatus) && !"SCHEDULED".equals(currentStatus)) {
            throw new IllegalStateException(
                    "Campaign can only be edited in DRAFT or SCHEDULED status. Current: " + currentStatus);
        }

        if (req.title() != null) c.setTitle(req.title().trim());
        if (req.body() != null) c.setBody(req.body().trim());
        if (req.navigationPayload() != null) c.setNavigationPayload(req.navigationPayload());
        if (req.audienceDefinition() != null) c.setAudienceDefinition(req.audienceDefinition());

        if (req.status() != null) {
            applyStatusTransition(c, req.status(), req.scheduledAt());
        }

        c.setUpdatedAt(OffsetDateTime.now());
        return campaignRepo.save(c);
    }

    @Transactional
    public NotificationCampaign startCampaign(UUID id) {
        NotificationCampaign c = campaignRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found: " + id));

        String currentStatus = c.getStatus();
        if (!"DRAFT".equals(currentStatus) && !"SCHEDULED".equals(currentStatus)) {
            throw new IllegalStateException(
                    "Campaign can only be started from DRAFT or SCHEDULED. Current: " + currentStatus);
        }

        c.setStatus("SENDING");
        c.setStartedAt(OffsetDateTime.now());
        c.setUpdatedAt(OffsetDateTime.now());
        return campaignRepo.save(c);
    }

    @Transactional
    public NotificationCampaign cancelCampaign(UUID id) {
        NotificationCampaign c = campaignRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found: " + id));

        String currentStatus = c.getStatus();
        if ("COMPLETED".equals(currentStatus) || "CANCELLED".equals(currentStatus)) {
            throw new IllegalStateException(
                    "Campaign is already in terminal state: " + currentStatus);
        }

        c.setStatus("CANCELLED");
        c.setCancelledAt(OffsetDateTime.now());
        c.setUpdatedAt(OffsetDateTime.now());
        return campaignRepo.save(c);
    }

    private void applyStatusTransition(NotificationCampaign c,
                                       String newStatus,
                                       OffsetDateTime scheduledAt) {
        switch (newStatus) {
            case "SCHEDULED" -> {
                if (scheduledAt == null) {
                    throw new IllegalArgumentException(
                            "scheduledAt is required to schedule a campaign.");
                }
                c.setStatus("SCHEDULED");
                c.setScheduledAt(scheduledAt);
            }
            case "DRAFT" -> c.setStatus("DRAFT");
            default -> throw new IllegalArgumentException(
                    "Status transition to " + newStatus + " is not allowed via update. "
                            + "Use dedicated start/cancel actions.");
        }
    }

    private void validateCampaignContent(String title, String body, String campaignKey) {
        if (title == null || title.isBlank())
            throw new IllegalArgumentException("Campaign title is required.");
        if (body == null || body.isBlank())
            throw new IllegalArgumentException("Campaign body is required.");
        if (campaignKey == null || campaignKey.isBlank())
            throw new IllegalArgumentException("Campaign key is required.");
        if (title.length() > 120)
            throw new IllegalArgumentException("Campaign title must be at most 120 characters.");
        if (body.length() > 300)
            throw new IllegalArgumentException("Campaign body must be at most 300 characters.");
    }
}
