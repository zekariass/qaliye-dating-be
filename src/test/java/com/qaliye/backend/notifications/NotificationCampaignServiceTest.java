package com.qaliye.backend.notifications;

import com.qaliye.backend.notifications.entity.NotificationCampaign;
import com.qaliye.backend.notifications.repository.NotificationCampaignRepository;
import com.qaliye.backend.notifications.service.NotificationCampaignService;
import com.qaliye.backend.notifications.service.NotificationCampaignService.CreateRequest;
import com.qaliye.backend.notifications.service.NotificationCampaignService.UpdateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationCampaignServiceTest {

    @Mock NotificationCampaignRepository campaignRepo;

    NotificationCampaignService service;
    UUID adminId = UUID.randomUUID();
    UUID campaignId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new NotificationCampaignService(campaignRepo);
    }

    @Test
    void createCampaign_validRequest_returnsDraft() {
        when(campaignRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        NotificationCampaign result = service.createCampaign(adminId,
                new CreateRequest("summer-2026", "Summer Sale!", "Check out our deals",
                        Map.of(), Map.of()));

        assertThat(result.getStatus()).isEqualTo("DRAFT");
        assertThat(result.getTitle()).isEqualTo("Summer Sale!");
        assertThat(result.getCampaignKey()).isEqualTo("summer-2026");
        assertThat(result.getCreatedByUserId()).isEqualTo(adminId);
    }

    @Test
    void createCampaign_blankTitle_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.createCampaign(adminId,
                new CreateRequest("key", "  ", "body", Map.of(), Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title");
    }

    @Test
    void createCampaign_titleTooLong_throwsIllegalArgument() {
        String longTitle = "a".repeat(121);
        assertThatThrownBy(() -> service.createCampaign(adminId,
                new CreateRequest("key", longTitle, "body", Map.of(), Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("120");
    }

    @Test
    void updateCampaign_draftStatus_canChangeToScheduled() {
        NotificationCampaign campaign = draftCampaign();
        when(campaignRepo.findById(campaignId)).thenReturn(Optional.of(campaign));
        when(campaignRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OffsetDateTime scheduledAt = OffsetDateTime.now().plusDays(1);
        NotificationCampaign updated = service.updateCampaign(campaignId,
                new UpdateRequest(null, null, null, null, "SCHEDULED", scheduledAt));

        assertThat(updated.getStatus()).isEqualTo("SCHEDULED");
        assertThat(updated.getScheduledAt()).isEqualTo(scheduledAt);
    }

    @Test
    void updateCampaign_scheduledStatus_requiresScheduledAt() {
        NotificationCampaign campaign = draftCampaign();
        when(campaignRepo.findById(campaignId)).thenReturn(Optional.of(campaign));

        assertThatThrownBy(() -> service.updateCampaign(campaignId,
                new UpdateRequest(null, null, null, null, "SCHEDULED", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheduledAt");
    }

    @Test
    void updateCampaign_sendingStatus_throwsIllegalArgument() {
        NotificationCampaign campaign = draftCampaign();
        when(campaignRepo.findById(campaignId)).thenReturn(Optional.of(campaign));

        assertThatThrownBy(() -> service.updateCampaign(campaignId,
                new UpdateRequest(null, null, null, null, "SENDING", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateCampaign_sendingCampaign_contentIsImmutable() {
        NotificationCampaign campaign = draftCampaign();
        campaign.setStatus("SENDING");
        campaign.setStartedAt(OffsetDateTime.now());
        when(campaignRepo.findById(campaignId)).thenReturn(Optional.of(campaign));

        assertThatThrownBy(() -> service.updateCampaign(campaignId,
                new UpdateRequest("New Title", null, null, null, null, null)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void startCampaign_draftStatus_becomeSending() {
        NotificationCampaign campaign = draftCampaign();
        when(campaignRepo.findById(campaignId)).thenReturn(Optional.of(campaign));
        when(campaignRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        NotificationCampaign result = service.startCampaign(campaignId);

        assertThat(result.getStatus()).isEqualTo("SENDING");
        assertThat(result.getStartedAt()).isNotNull();
    }

    @Test
    void startCampaign_completedCampaign_throwsIllegalState() {
        NotificationCampaign campaign = draftCampaign();
        campaign.setStatus("COMPLETED");
        when(campaignRepo.findById(campaignId)).thenReturn(Optional.of(campaign));

        assertThatThrownBy(() -> service.startCampaign(campaignId))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancelCampaign_sending_becomesCancelled() {
        NotificationCampaign campaign = draftCampaign();
        campaign.setStatus("SENDING");
        campaign.setStartedAt(OffsetDateTime.now());
        when(campaignRepo.findById(campaignId)).thenReturn(Optional.of(campaign));
        when(campaignRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        NotificationCampaign result = service.cancelCampaign(campaignId);

        assertThat(result.getStatus()).isEqualTo("CANCELLED");
        assertThat(result.getCancelledAt()).isNotNull();
    }

    @Test
    void cancelCampaign_alreadyCancelled_throwsIllegalState() {
        NotificationCampaign campaign = draftCampaign();
        campaign.setStatus("CANCELLED");
        campaign.setCancelledAt(OffsetDateTime.now());
        when(campaignRepo.findById(campaignId)).thenReturn(Optional.of(campaign));

        assertThatThrownBy(() -> service.cancelCampaign(campaignId))
                .isInstanceOf(IllegalStateException.class);
    }

    private NotificationCampaign draftCampaign() {
        NotificationCampaign c = new NotificationCampaign();
        c.setId(campaignId);
        c.setCampaignKey("test-campaign");
        c.setTitle("Test Campaign");
        c.setBody("Test body");
        c.setNavigationPayload(Map.of());
        c.setAudienceDefinition(Map.of());
        c.setStatus("DRAFT");
        c.setCreatedByUserId(adminId);
        c.setCreatedAt(OffsetDateTime.now());
        c.setUpdatedAt(OffsetDateTime.now());
        return c;
    }
}
