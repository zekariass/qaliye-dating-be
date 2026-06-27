package com.qaliye.backend.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaliye.backend.activity.dto.ActivityVisibilityRequest;
import com.qaliye.backend.activity.dto.ActivityVisibilityResponse;
import com.qaliye.backend.activity.dto.BatchStatusRequest;
import com.qaliye.backend.activity.dto.BatchStatusResponse;
import com.qaliye.backend.activity.dto.HeartbeatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ActivityController.class,
        excludeAutoConfiguration = {
                org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration.class
        })
class ActivityControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean ActivityStatusService activityStatusService;
    @MockBean ActivityVisibilitySettingsService visibilityService;

    UUID callerId = UUID.randomUUID();

    @Test
    void heartbeat_authenticated_returns200WithStatus() throws Exception {
        when(activityStatusService.heartbeat(any()))
                .thenReturn(new ActivityStatusService.HeartbeatResult(ActivityStatus.ONLINE, true));

        mockMvc.perform(post("/api/v1/activity/heartbeat")
                        .with(jwt().jwt(j -> j.subject(callerId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activity_status").value("ONLINE"))
                .andExpect(jsonPath("$.show_activity_status").value(true));
    }

    @Test
    void heartbeat_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/activity/heartbeat"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getBatchStatuses_authenticated_returns200() throws Exception {
        UUID targetId = UUID.randomUUID();
        when(activityStatusService.getBatchStatuses(any(), anyList()))
                .thenReturn(List.of(new ActivityStatusService.BatchStatusItem(targetId, ActivityStatus.RECENTLY_ACTIVE)));

        BatchStatusRequest req = new BatchStatusRequest(List.of(targetId));

        mockMvc.perform(post("/api/v1/activity/statuses")
                        .with(jwt().jwt(j -> j.subject(callerId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].user_id").value(targetId.toString()))
                .andExpect(jsonPath("$.items[0].activity_status").value("RECENTLY_ACTIVE"));
    }

    @Test
    void getBatchStatuses_emptyList_returns400() throws Exception {
        BatchStatusRequest req = new BatchStatusRequest(List.of());

        mockMvc.perform(post("/api/v1/activity/statuses")
                        .with(jwt().jwt(j -> j.subject(callerId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getBatchStatuses_unauthenticated_returns401() throws Exception {
        BatchStatusRequest req = new BatchStatusRequest(List.of(UUID.randomUUID()));

        mockMvc.perform(post("/api/v1/activity/statuses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateVisibility_setFalse_returns200WithHidden() throws Exception {
        when(visibilityService.updateVisibility(any(), anyBoolean()))
                .thenReturn(new ActivityVisibilityResponse(false, ActivityStatus.HIDDEN));

        ActivityVisibilityRequest req = new ActivityVisibilityRequest(false);

        mockMvc.perform(patch("/api/v1/users/me/activity-visibility")
                        .with(jwt().jwt(j -> j.subject(callerId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.show_activity_status").value(false))
                .andExpect(jsonPath("$.activity_status").value("HIDDEN"));
    }

    @Test
    void updateVisibility_missingBody_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me/activity-visibility")
                        .with(jwt().jwt(j -> j.subject(callerId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateVisibility_unauthenticated_returns401() throws Exception {
        ActivityVisibilityRequest req = new ActivityVisibilityRequest(true);

        mockMvc.perform(patch("/api/v1/users/me/activity-visibility")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getBatchStatuses_tooManyIds_returns400() throws Exception {
        List<UUID> tooMany = java.util.stream.Stream.generate(UUID::randomUUID).limit(51).toList();
        BatchStatusRequest req = new BatchStatusRequest(tooMany);

        mockMvc.perform(post("/api/v1/activity/statuses")
                        .with(jwt().jwt(j -> j.subject(callerId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getBatchStatuses_authorizedUserOmittedForUnauthorized_returnsOnlyAuthorized() throws Exception {
        UUID authorizedId = UUID.randomUUID();
        UUID unauthorizedId = UUID.randomUUID();

        when(activityStatusService.getBatchStatuses(any(), anyList()))
                .thenReturn(List.of(new ActivityStatusService.BatchStatusItem(authorizedId, ActivityStatus.OFFLINE)));

        BatchStatusRequest req = new BatchStatusRequest(List.of(authorizedId, unauthorizedId));

        mockMvc.perform(post("/api/v1/activity/statuses")
                        .with(jwt().jwt(j -> j.subject(callerId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].user_id").value(authorizedId.toString()));
    }
}
