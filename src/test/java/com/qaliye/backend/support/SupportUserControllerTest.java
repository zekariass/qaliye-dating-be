package com.qaliye.backend.support;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import com.qaliye.backend.support.api.SupportUserController;
import com.qaliye.backend.support.dto.MarkReadRequest;
import com.qaliye.backend.support.dto.SupportConversationDto;
import com.qaliye.backend.support.dto.SupportMessageDto;
import com.qaliye.backend.support.dto.SupportMessagePageDto;
import com.qaliye.backend.support.service.SupportConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SupportUserControllerTest {

    @Mock SupportConversationService service;
    @InjectMocks SupportUserController controller;

    MockMvc mockMvc;
    JsonMapper jsonMapper;

    UUID callerId = UUID.randomUUID();
    UUID convId   = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jsonMapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
        Jwt jwt = Jwt.withTokenValue("test-token")
                .subject(callerId.toString())
                .header("alg", "none")
                .build();
        Authentication auth = new TestingAuthenticationToken(jwt, null);
        SecurityContextHolder.getContext().setAuthentication(auth);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new JacksonJsonHttpMessageConverter(jsonMapper))
                .build();
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private SupportConversationDto sampleConversation() {
        return new SupportConversationDto(convId, "IDLE", 0L, 1L,
                null, null, null, OffsetDateTime.now());
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/support/conversation
    // -------------------------------------------------------------------------

    @Test
    void getConversation_found_returns200() throws Exception {
        when(service.getConversation(any())).thenReturn(sampleConversation());

        mockMvc.perform(get("/api/v1/support/conversation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IDLE"))
                .andExpect(jsonPath("$.id").value(convId.toString()));
    }

    @Test
    void getConversation_notFound_returns404() throws Exception {
        when(service.getConversation(any()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Support conversation not found"));

        mockMvc.perform(get("/api/v1/support/conversation"))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/support/conversation/messages
    // -------------------------------------------------------------------------

    @Test
    void listMessages_returns200() throws Exception {
        when(service.listMessages(any(), isNull(), anyInt()))
                .thenReturn(new SupportMessagePageDto(List.of(), null));

        mockMvc.perform(get("/api/v1/support/conversation/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages").isArray());
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/support/conversation/messages
    // -------------------------------------------------------------------------

    @Test
    void sendMessage_textOnly_returns201() throws Exception {
        UUID clientMsgId = UUID.randomUUID();
        SupportMessageDto dto = new SupportMessageDto(UUID.randomUUID(), convId, 1L, "USER",
                "Test User", "hello", OffsetDateTime.now(), List.of());
        when(service.sendMessage(any(), eq(clientMsgId), eq("hello"), any(), any())).thenReturn(dto);

        mockMvc.perform(multipart("/api/v1/support/conversation/messages")
                        .param("clientMessageId", clientMsgId.toString())
                        .param("body", "hello"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.body").value("hello"))
                .andExpect(jsonPath("$.sender_type").value("USER"));
    }

    @Test
    void sendMessage_serviceThrowsConflict_returns409() throws Exception {
        UUID clientMsgId = UUID.randomUUID();
        when(service.sendMessage(any(), any(), any(), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT"));

        mockMvc.perform(multipart("/api/v1/support/conversation/messages")
                        .param("clientMessageId", clientMsgId.toString())
                        .param("body", "hello"))
                .andExpect(status().isConflict());
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/support/conversation/read
    // -------------------------------------------------------------------------

    @Test
    void markRead_validRequest_returns204() throws Exception {
        doNothing().when(service).markRead(any(), anyLong());
        MarkReadRequest req = new MarkReadRequest(5L);

        mockMvc.perform(post("/api/v1/support/conversation/read")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());
    }

    @Test
    void markRead_nullSequence_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/support/conversation/read")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/support/conversation/close
    // -------------------------------------------------------------------------

    @Test
    void close_returns204() throws Exception {
        doNothing().when(service).close(any());

        mockMvc.perform(post("/api/v1/support/conversation/close"))
                .andExpect(status().isNoContent());
    }

    @Test
    void close_serviceThrowsUnprocessable_returns422() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Active conversation not found"))
                .when(service).close(any());

        mockMvc.perform(post("/api/v1/support/conversation/close"))
                .andExpect(status().isUnprocessableEntity());
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/support/attachments/{id}/download-url
    // -------------------------------------------------------------------------

    @Test
    void getDownloadUrl_found_returns200() throws Exception {
        UUID attachmentId = UUID.randomUUID();
        when(service.getAttachmentDownloadUrl(any(), eq(attachmentId)))
                .thenReturn("https://example.com/signed");

        mockMvc.perform(get("/api/v1/support/attachments/{id}/download-url", attachmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.download_url").value("https://example.com/signed"));
    }

    @Test
    void getDownloadUrl_notOwner_returns403() throws Exception {
        UUID attachmentId = UUID.randomUUID();
        when(service.getAttachmentDownloadUrl(any(), eq(attachmentId)))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied"));

        mockMvc.perform(get("/api/v1/support/attachments/{id}/download-url", attachmentId))
                .andExpect(status().isForbidden());
    }
}
