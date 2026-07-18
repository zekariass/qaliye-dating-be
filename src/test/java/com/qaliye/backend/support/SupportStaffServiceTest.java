package com.qaliye.backend.support;

import com.qaliye.backend.storage.SupabaseStorageService;
import com.qaliye.backend.support.dto.StaffConversationDetailDto;
import com.qaliye.backend.support.dto.SupportInternalNoteDto;
import com.qaliye.backend.support.dto.SupportMessagePageDto;
import com.qaliye.backend.support.repository.SupportConversationRepository;
import com.qaliye.backend.support.repository.SupportConversationRepository.ConversationRow;
import com.qaliye.backend.support.repository.SupportMessageRepository;
import com.qaliye.backend.support.repository.SupportMessageRepository.AttachmentRow;
import com.qaliye.backend.support.repository.SupportNoteRepository;
import com.qaliye.backend.support.repository.SupportNoteRepository.NoteRow;
import com.qaliye.backend.support.service.SupportConversationService;
import com.qaliye.backend.support.service.SupportStaffService;
import com.qaliye.backend.user.UserStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SupportStaffServiceTest {

    @Mock SupportConversationRepository convRepo;
    @Mock SupportMessageRepository msgRepo;
    @Mock SupportNoteRepository noteRepo;
    @Mock SupabaseStorageService storageService;
    @Mock UserStatusService userStatusService;
    @Mock SupportConversationService conversationService;

    SupportProperties props;
    SupportStaffService service;

    UUID staffId = UUID.randomUUID();
    UUID convId  = UUID.randomUUID();
    UUID userId  = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        props = new SupportProperties();
        service = new SupportStaffService(convRepo, msgRepo, noteRepo, storageService,
                props, userStatusService, conversationService);
    }

    private void asStaff(String role) {
        when(userStatusService.getStatus(staffId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", role, "en"));
    }

    private ConversationRow conv(String status) {
        return new ConversationRow(
                convId, userId, "Test User", status, 3, staffId,
                5L, 0L, 0L, OffsetDateTime.now(), "USER",
                OffsetDateTime.now(), null, OffsetDateTime.now(),
                null, null, null,
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    // -------------------------------------------------------------------------
    // requireStaffRole
    // -------------------------------------------------------------------------

    @Test
    void requireStaffRole_regularUser_throws403() {
        when(userStatusService.getStatus(staffId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "USER", "en"));

        assertThatThrownBy(() -> service.requireStaffRole(staffId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(403));
    }

    @Test
    void requireStaffRole_moderator_passes() {
        asStaff("MODERATOR");
        service.requireStaffRole(staffId);
    }

    @Test
    void requireStaffRole_admin_passes() {
        asStaff("ADMIN");
        service.requireStaffRole(staffId);
    }

    @Test
    void requireStaffRole_nullStatus_throws403() {
        when(userStatusService.getStatus(staffId)).thenReturn(null);

        assertThatThrownBy(() -> service.requireStaffRole(staffId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(403));
    }

    // -------------------------------------------------------------------------
    // listConversations
    // -------------------------------------------------------------------------

    @Test
    void listConversations_moderator_queriesRepo() {
        asStaff("MODERATOR");
        when(convRepo.listForQueue(any(), any(), any(), any(), eq(25), eq(0)))
                .thenReturn(List.of());

        var result = service.listConversations(staffId, null, null, null, null, 25, 0);

        assertThat(result).isEmpty();
        verify(convRepo).listForQueue(any(), any(), any(), any(), eq(25), eq(0));
    }

    @Test
    void listConversations_nonStaff_throws403() {
        when(userStatusService.getStatus(staffId))
                .thenReturn(new UserStatusService.UserStatus("ACTIVE", "USER", "en"));

        assertThatThrownBy(() -> service.listConversations(staffId, null, null, null, null, 25, 0))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(403));
    }

    // -------------------------------------------------------------------------
    // getConversationDetail
    // -------------------------------------------------------------------------

    @Test
    void getConversationDetail_admin_returnsDetail() {
        asStaff("ADMIN");
        when(convRepo.findById(convId)).thenReturn(Optional.of(conv("WAITING_STAFF")));
        when(convRepo.findMyReadSequence(convId, staffId)).thenReturn(3L);

        StaffConversationDetailDto detail = service.getConversationDetail(staffId, convId);

        assertThat(detail.id()).isEqualTo(convId);
        assertThat(detail.status()).isEqualTo("WAITING_STAFF");
        assertThat(detail.myLastReadSequence()).isEqualTo(3L);
    }

    @Test
    void getConversationDetail_notFound_throws404() {
        asStaff("MODERATOR");
        when(convRepo.findById(convId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getConversationDetail(staffId, convId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
    }

    // -------------------------------------------------------------------------
    // listMessages
    // -------------------------------------------------------------------------

    @Test
    void listMessages_admin_queriesRepo() {
        asStaff("ADMIN");
        when(convRepo.findById(convId)).thenReturn(Optional.of(conv("WAITING_STAFF")));
        when(msgRepo.findMessages(eq(convId), any(), anyInt())).thenReturn(List.of());

        SupportMessagePageDto page = service.listMessages(staffId, convId, null, 50);

        assertThat(page.messages()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // sendMessage
    // -------------------------------------------------------------------------

    @Test
    void sendMessage_closedConversation_throws422() {
        asStaff("MODERATOR");
        when(convRepo.findById(convId)).thenReturn(Optional.of(conv("CLOSED")));
        PSQLException psql = new PSQLException(
                "Conversation is closed; reopen it explicitly before replying",
                PSQLState.UNKNOWN_STATE);
        when(msgRepo.callAppendStaffMessage(any(), any(), any(), any(), any(), any()))
                .thenThrow(new UncategorizedSQLException("task", "sql", psql));

        assertThatThrownBy(() -> service.sendMessage(staffId, convId, UUID.randomUUID(), "hi", List.of(), null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(422));
    }

    @Test
    void sendMessage_noBodyNoFiles_throws400() {
        asStaff("MODERATOR");
        when(convRepo.findById(convId)).thenReturn(Optional.of(conv("WAITING_STAFF")));

        assertThatThrownBy(() -> service.sendMessage(staffId, convId, UUID.randomUUID(), null, List.of(), null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));
    }

    // -------------------------------------------------------------------------
    // addNote
    // -------------------------------------------------------------------------

    @Test
    void addNote_success_returnsDto() {
        asStaff("MODERATOR");
        UUID noteId = UUID.randomUUID();
        NoteRow note = new NoteRow(noteId, convId, staffId, "Admin Joe", "test note", OffsetDateTime.now());
        when(noteRepo.callAppendNote(eq(convId), eq(staffId), any(), eq("test note"), any()))
                .thenReturn(note);

        SupportInternalNoteDto dto = service.addNote(staffId, convId, UUID.randomUUID(), "test note");

        assertThat(dto.body()).isEqualTo("test note");
        assertThat(dto.staffUserId()).isEqualTo(staffId);
        assertThat(dto.staffDisplayName()).isEqualTo("Admin Joe");
    }

    @Test
    void addNote_idleConversation_throws422() {
        asStaff("MODERATOR");
        PSQLException psql = new PSQLException(
                "Cannot add an internal note to an idle conversation",
                PSQLState.UNKNOWN_STATE);
        when(noteRepo.callAppendNote(any(), any(), any(), any(), any()))
                .thenThrow(new UncategorizedSQLException("task", "sql", psql));

        assertThatThrownBy(() -> service.addNote(staffId, convId, UUID.randomUUID(), "note"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(422));
    }

    @Test
    void addNote_idempotencyConflict_throws409() {
        asStaff("MODERATOR");
        UUID clientNoteId = UUID.randomUUID();
        PSQLException psql = new PSQLException(
                "Idempotency conflict: client note ID was reused with a different payload",
                PSQLState.UNKNOWN_STATE);
        when(noteRepo.callAppendNote(any(), any(), eq(clientNoteId), any(), any()))
                .thenThrow(new UncategorizedSQLException("task", "sql", psql));

        assertThatThrownBy(() -> service.addNote(staffId, convId, clientNoteId, "note"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));
    }

    // -------------------------------------------------------------------------
    // listNotes
    // -------------------------------------------------------------------------

    @Test
    void listNotes_moderator_queriesRepo() {
        asStaff("MODERATOR");
        when(convRepo.findById(convId)).thenReturn(Optional.of(conv("WAITING_STAFF")));
        when(noteRepo.findNotes(eq(convId), anyInt(), anyInt())).thenReturn(List.of());

        var notes = service.listNotes(staffId, convId, 50, 0);

        assertThat(notes).isEmpty();
        verify(noteRepo).findNotes(eq(convId), anyInt(), anyInt());
    }

    // -------------------------------------------------------------------------
    // markRead / assign / priority / close / reopen
    // -------------------------------------------------------------------------

    @Test
    void markRead_callsRpc() {
        asStaff("MODERATOR");

        service.markRead(staffId, convId, 10L);

        verify(convRepo).callMarkReadByStaff(convId, staffId, 10L);
    }

    @Test
    void assign_callsRpc() {
        asStaff("MODERATOR");
        UUID assignedTo = UUID.randomUUID();

        service.assign(staffId, convId, assignedTo);

        verify(convRepo).callAssign(convId, staffId, assignedTo);
    }

    @Test
    void setPriority_callsRpc() {
        asStaff("ADMIN");

        service.setPriority(staffId, convId, 1);

        verify(convRepo).callSetPriority(convId, staffId, 1);
    }

    @Test
    void setPriority_rpcRejectsBadPriority_throws400() {
        asStaff("ADMIN");
        PSQLException psql = new PSQLException("priority must be between 1 and 5", PSQLState.UNKNOWN_STATE);
        doThrow(new UncategorizedSQLException("task", "sql", psql))
                .when(convRepo).callSetPriority(convId, staffId, 99);

        assertThatThrownBy(() -> service.setPriority(staffId, convId, 99))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void close_moderator_callsRpc() {
        asStaff("MODERATOR");

        service.close(staffId, convId);

        verify(convRepo).callCloseByStaff(convId, staffId);
    }

    @Test
    void reopen_admin_callsRpc() {
        asStaff("ADMIN");

        service.reopen(staffId, convId);

        verify(convRepo).callReopen(convId, staffId);
    }

    @Test
    void reopen_notClosed_throws422() {
        asStaff("ADMIN");
        PSQLException psql = new PSQLException(
                "Only a closed conversation may be reopened", PSQLState.UNKNOWN_STATE);
        doThrow(new UncategorizedSQLException("task", "sql", psql))
                .when(convRepo).callReopen(convId, staffId);

        assertThatThrownBy(() -> service.reopen(staffId, convId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(422));
    }

    // -------------------------------------------------------------------------
    // getAttachmentDownloadUrl
    // -------------------------------------------------------------------------

    @Test
    void getAttachmentDownloadUrl_staff_returnsSignedUrl() {
        asStaff("MODERATOR");
        UUID attachmentId = UUID.randomUUID();
        AttachmentRow att = new AttachmentRow(attachmentId, UUID.randomUUID(),
                "support-attachments", "support/path/file.pdf",
                "file.pdf", "application/pdf", 2048L, "DOCUMENT", null, OffsetDateTime.now());
        when(msgRepo.findAttachmentWithConversationUserId(attachmentId)).thenReturn(Optional.of(att));
        when(storageService.generateSignedUrl("support-attachments", "support/path/file.pdf", 300))
                .thenReturn("https://storage.example.com/signed-staff");

        String url = service.getAttachmentDownloadUrl(staffId, attachmentId);

        assertThat(url).isEqualTo("https://storage.example.com/signed-staff");
    }

    @Test
    void getAttachmentDownloadUrl_notFound_throws404() {
        asStaff("MODERATOR");
        UUID attachmentId = UUID.randomUUID();
        when(msgRepo.findAttachmentWithConversationUserId(attachmentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAttachmentDownloadUrl(staffId, attachmentId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
    }
}
