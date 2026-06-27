package com.qaliye.backend.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaliye.backend.chat.controller.ChatController;
import com.qaliye.backend.chat.dto.*;
import com.qaliye.backend.chat.exception.*;
import com.qaliye.backend.chat.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ChatController.class,
        excludeAutoConfiguration = {
                org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration.class
        })
class ChatControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean ChatQueryService queryService;
    @MockBean MessageCommandService messageCommandService;
    @MockBean ReceiptService receiptService;
    @MockBean ChatNotificationSettingsService notificationSettingsService;
    @MockBean com.qaliye.backend.chat.exception.ChatExceptionHandler chatExceptionHandler;

    UUID callerId = UUID.randomUUID();
    UUID matchId  = UUID.randomUUID();

    @Test
    void getInbox_authenticated_returns200() throws Exception {
        InboxResponse inbox = new InboxResponse(List.of(), null);
        when(queryService.getInbox(any(), anyString(), isNull(), anyInt())).thenReturn(inbox);

        mockMvc.perform(get("/api/v1/chat/matches")
                        .with(jwt().jwt(j -> j.subject(callerId.toString()))))
                .andExpect(status().isOk());
    }

    @Test
    void getInbox_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/chat/matches"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sendMessage_newMessage_returns201() throws Exception {
        ChatMessageDto dto = new ChatMessageDto(UUID.randomUUID(), matchId, 1L,
                callerId, "TEXT", "hi", "SENT", Instant.now());
        when(messageCommandService.sendMessage(any(), eq(matchId), any()))
                .thenReturn(new MessageCommandService.SendResult(dto, true));

        SendMessageRequest req = new SendMessageRequest();
        req.setClientMessageId(UUID.randomUUID());
        req.setMessageType("TEXT");
        req.setBody("hi");

        mockMvc.perform(post("/api/v1/chat/matches/{id}/messages", matchId)
                        .with(jwt().jwt(j -> j.subject(callerId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());
    }

    @Test
    void sendMessage_duplicate_returns200() throws Exception {
        ChatMessageDto dto = new ChatMessageDto(UUID.randomUUID(), matchId, 1L,
                callerId, "TEXT", "hi", "SENT", Instant.now());
        when(messageCommandService.sendMessage(any(), eq(matchId), any()))
                .thenReturn(new MessageCommandService.SendResult(dto, false));

        SendMessageRequest req = new SendMessageRequest();
        req.setClientMessageId(UUID.randomUUID());
        req.setMessageType("TEXT");
        req.setBody("hi");

        mockMvc.perform(post("/api/v1/chat/matches/{id}/messages", matchId)
                        .with(jwt().jwt(j -> j.subject(callerId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
    }

    @Test
    void markDelivered_returns204() throws Exception {
        doNothing().when(receiptService).markDelivered(any(), any(), anyLong());

        MarkReceiptRequest req = new MarkReceiptRequest();
        req.setUpToSequence(5L);

        mockMvc.perform(post("/api/v1/chat/matches/{id}/receipts/delivered", matchId)
                        .with(jwt().jwt(j -> j.subject(callerId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());
    }

    @Test
    void markRead_returns204() throws Exception {
        doNothing().when(receiptService).markRead(any(), any(), anyLong());

        MarkReceiptRequest req = new MarkReceiptRequest();
        req.setUpToSequence(3L);

        mockMvc.perform(post("/api/v1/chat/matches/{id}/receipts/read", matchId)
                        .with(jwt().jwt(j -> j.subject(callerId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());
    }

    @Test
    void updateNotificationSettings_returns204() throws Exception {
        doNothing().when(notificationSettingsService).updateMuteSetting(any(), any(), any());

        MuteSettingsRequest req = new MuteSettingsRequest();
        req.setMutedUntil(Instant.now().plusSeconds(3600));

        mockMvc.perform(patch("/api/v1/chat/matches/{id}/notification-settings", matchId)
                        .with(jwt().jwt(j -> j.subject(callerId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());
    }
}
