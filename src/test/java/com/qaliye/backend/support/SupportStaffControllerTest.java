package com.qaliye.backend.support;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import com.qaliye.backend.support.api.SupportStaffController;
import com.qaliye.backend.support.dto.*;
import com.qaliye.backend.support.service.SupportStaffService;
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
class SupportStaffControllerTest {

    @Mock SupportStaffService service;
    @InjectMocks SupportStaffController controller;

    MockMvc mockMvc;
    JsonMapper jsonMapper;

    UUID staffId = UUID.randomUUID();
    UUID convId  = UUID.randomUUID();
    UUID userId  = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jsonMapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
        Jwt jwt = Jwt.withTokenValue("test-token")
                .subject(staffId.toString())
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

    // -------------------------------------------------------------------------
    // GET /api/v1/staff/support/conversations
    // -------------------------------------------------------------------------

    @Test
    void listConversations_returns200() throws Exception {
        when(service.listConversations(any(), isNull(), isNull(), isNull(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/staff/support/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void listConversations_forbidden_returns403() throws Exception {
        when(service.listConversations(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "ADMIN or MODERATOR role required"));

        mockMvc.perform(get("/api/v1/staff/support/conversations"))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/staff/support/conversations/{id}
    // -------------------------------------------------------------------------

    @Test
    void getConversationDetail_found_returns200() throws Exception {
        StaffConversationDetailDto detail = new StaffConversationDetailDto(
                convId, userId, "Test User", "WAITING_STAFF", 3, staffId,
                5L, 0L, 0L, 0L, OffsetDateTime.now(), null,
                OffsetDateTime.now(), OffsetDateTime.now(), "USER",
                null, null, OffsetDateTime.now(), OffsetDateTime.now());
        when(service.getConversationDetail(any(), eq(convId))).thenReturn(detail);

        mockMvc.perform(get("/api/v1/staff/support/conversations/{id}", convId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING_STAFF"))
                .andExpect(jsonPath("$.priority").value(3));
    }

    @Test
    void getConversationDetail_notFound_returns404() throws Exception {
        when(service.getConversationDetail(any(), eq(convId)))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Support conversation not found"));

        mockMvc.perform(get("/api/v1/staff/support/conversations/{id}", convId))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/staff/support/conversations/{id}/messages
    // -------------------------------------------------------------------------

    @Test
    void listMessages_returns200() throws Exception {
        when(service.listMessages(any(), eq(convId), isNull(), anyInt()))
                .thenReturn(new SupportMessagePageDto(List.of(), null));

        mockMvc.perform(get("/api/v1/staff/support/conversations/{id}/messages", convId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages").isArray());
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/staff/support/conversations/{id}/messages
    // -------------------------------------------------------------------------

    @Test
    void sendMessage_textOnly_returns201() throws Exception {
        UUID clientMsgId = UUID.randomUUID();
        SupportMessageDto dto = new SupportMessageDto(UUID.randomUUID(), convId, 1L, "STAFF",
                "Admin Joe", "reply", OffsetDateTime.now(), List.of());
        when(service.sendMessage(any(), eq(convId), eq(clientMsgId), eq("reply"), any(), any()))
                .thenReturn(dto);

        mockMvc.perform(multipart("/api/v1/staff/support/conversations/{id}/messages", convId)
                        .param("clientMessageId", clientMsgId.toString())
                        .param("body", "reply"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.body").value("reply"));
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/staff/support/conversations/{id}/notes
    // -------------------------------------------------------------------------

    @Test
    void listNotes_returns200() throws Exception {
        when(service.listNotes(any(), eq(convId), anyInt(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/staff/support/conversations/{id}/notes", convId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/staff/support/conversations/{id}/notes
    // -------------------------------------------------------------------------

    @Test
    void addNote_validRequest_returns201() throws Exception {
        UUID noteId = UUID.randomUUID();
        UUID clientNoteId = UUID.randomUUID();
        SupportInternalNoteDto dto = new SupportInternalNoteDto(
                noteId, convId, staffId, "Admin Joe", "note body", OffsetDateTime.now());
        AddNoteRequest req = new AddNoteRequest(clientNoteId, "note body");
        when(service.addNote(any(), eq(convId), eq(clientNoteId), eq("note body"))).thenReturn(dto);

        mockMvc.perform(post("/api/v1/staff/support/conversations/{id}/notes", convId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.body").value("note body"));
    }

    @Test
    void addNote_blankBody_returns400() throws Exception {
        AddNoteRequest req = new AddNoteRequest(UUID.randomUUID(), "   ");

        mockMvc.perform(post("/api/v1/staff/support/conversations/{id}/notes", convId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addNote_missingClientNoteId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/staff/support/conversations/{id}/notes", convId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"a note\"}"))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/staff/support/conversations/{id}/read
    // -------------------------------------------------------------------------

    @Test
    void markRead_validRequest_returns204() throws Exception {
        doNothing().when(service).markRead(any(), eq(convId), eq(5L));
        MarkReadRequest req = new MarkReadRequest(5L);

        mockMvc.perform(post("/api/v1/staff/support/conversations/{id}/read", convId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());
    }

    @Test
    void markRead_nullSequence_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/staff/support/conversations/{id}/read", convId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // PATCH /api/v1/staff/support/conversations/{id}/assignment
    // -------------------------------------------------------------------------

    @Test
    void assign_validRequest_returns204() throws Exception {
        UUID assignedTo = UUID.randomUUID();
        doNothing().when(service).assign(any(), eq(convId), eq(assignedTo));
        AssignmentRequest req = new AssignmentRequest(assignedTo);

        mockMvc.perform(patch("/api/v1/staff/support/conversations/{id}/assignment", convId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());
    }

    // -------------------------------------------------------------------------
    // PATCH /api/v1/staff/support/conversations/{id}/priority
    // -------------------------------------------------------------------------

    @Test
    void setPriority_valid_returns204() throws Exception {
        doNothing().when(service).setPriority(any(), eq(convId), eq(1));
        PriorityRequest req = new PriorityRequest(1);

        mockMvc.perform(patch("/api/v1/staff/support/conversations/{id}/priority", convId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());
    }

    @Test
    void setPriority_tooHigh_returns400() throws Exception {
        PriorityRequest req = new PriorityRequest(6);

        mockMvc.perform(patch("/api/v1/staff/support/conversations/{id}/priority", convId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void setPriority_tooLow_returns400() throws Exception {
        PriorityRequest req = new PriorityRequest(0);

        mockMvc.perform(patch("/api/v1/staff/support/conversations/{id}/priority", convId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/staff/support/conversations/{id}/close
    // -------------------------------------------------------------------------

    @Test
    void close_returns204() throws Exception {
        doNothing().when(service).close(any(), eq(convId));

        mockMvc.perform(post("/api/v1/staff/support/conversations/{id}/close", convId))
                .andExpect(status().isNoContent());
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/staff/support/conversations/{id}/reopen
    // -------------------------------------------------------------------------

    @Test
    void reopen_returns204() throws Exception {
        doNothing().when(service).reopen(any(), eq(convId));

        mockMvc.perform(post("/api/v1/staff/support/conversations/{id}/reopen", convId))
                .andExpect(status().isNoContent());
    }

    @Test
    void reopen_notClosed_returns422() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Only a closed conversation may be reopened"))
                .when(service).reopen(any(), eq(convId));

        mockMvc.perform(post("/api/v1/staff/support/conversations/{id}/reopen", convId))
                .andExpect(status().isUnprocessableEntity());
    }

    // -------------------------------------------------------------------------
    // GET download-url
    // -------------------------------------------------------------------------

    @Test
    void getDownloadUrl_returns200() throws Exception {
        UUID attachmentId = UUID.randomUUID();
        when(service.getAttachmentDownloadUrl(any(), eq(attachmentId)))
                .thenReturn("https://example.com/signed-staff");

        mockMvc.perform(get("/api/v1/staff/support/conversations/{cid}/attachments/{aid}/download-url",
                        convId, attachmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.download_url").value("https://example.com/signed-staff"));
    }
}
