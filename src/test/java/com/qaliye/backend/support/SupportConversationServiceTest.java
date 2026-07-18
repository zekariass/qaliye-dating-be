package com.qaliye.backend.support;

import com.qaliye.backend.storage.SupabaseStorageService;
import com.qaliye.backend.support.dto.SupportConversationDto;
import com.qaliye.backend.support.dto.SupportMessageDto;
import com.qaliye.backend.support.dto.SupportMessagePageDto;
import com.qaliye.backend.support.repository.SupportConversationRepository;
import com.qaliye.backend.support.repository.SupportConversationRepository.ConversationRow;
import com.qaliye.backend.support.repository.SupportMessageRepository;
import com.qaliye.backend.support.repository.SupportMessageRepository.AttachmentRow;
import com.qaliye.backend.support.repository.SupportMessageRepository.MessageRow;
import com.qaliye.backend.support.service.SupportConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupportConversationServiceTest {

    @Mock SupportConversationRepository convRepo;
    @Mock SupportMessageRepository msgRepo;
    @Mock SupabaseStorageService storageService;

    SupportProperties props;
    SupportConversationService service;

    UUID userId = UUID.randomUUID();
    UUID convId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        props = new SupportProperties();
        service = new SupportConversationService(convRepo, msgRepo, storageService, props);
    }

    private ConversationRow conv(String status) {
        return new ConversationRow(
                convId, userId, null, status, 3, null,
                1L, 0L, 0L, null, null,
                null, null, null, null, null, null,
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    private MessageRow msg(String body) {
        return new MessageRow(UUID.randomUUID(), convId, 1L, "USER", "Test User", body, OffsetDateTime.now());
    }

    // -------------------------------------------------------------------------
    // getConversation
    // -------------------------------------------------------------------------

    @Test
    void getConversation_found_returnsDto() {
        when(convRepo.findByUserId(userId)).thenReturn(Optional.of(conv("IDLE")));

        SupportConversationDto dto = service.getConversation(userId);

        assertThat(dto.id()).isEqualTo(convId);
        assertThat(dto.status()).isEqualTo("IDLE");
    }

    @Test
    void getConversation_notFound_throws404() {
        when(convRepo.findByUserId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getConversation(userId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
    }

    // -------------------------------------------------------------------------
    // listMessages
    // -------------------------------------------------------------------------

    @Test
    void listMessages_returnsPageOfMessages() {
        when(convRepo.findByUserId(userId)).thenReturn(Optional.of(conv("WAITING_STAFF")));
        MessageRow m = msg("hello");
        when(msgRepo.findMessages(eq(convId), any(), anyInt())).thenReturn(List.of(m));
        when(msgRepo.findAttachmentsByMessageIds(any())).thenReturn(List.of());

        SupportMessagePageDto page = service.listMessages(userId, null, 25);

        assertThat(page.messages()).hasSize(1);
        assertThat(page.messages().get(0).body()).isEqualTo("hello");
        assertThat(page.nextBeforeSequence()).isNull();
    }

    @Test
    void listMessages_fullPage_returnsCursor() {
        when(convRepo.findByUserId(userId)).thenReturn(Optional.of(conv("WAITING_STAFF")));
        List<MessageRow> batch = List.of(
                new MessageRow(UUID.randomUUID(), convId, 3L, "USER", "Test User", "c", OffsetDateTime.now()),
                new MessageRow(UUID.randomUUID(), convId, 2L, "STAFF", "Admin Joe", "b", OffsetDateTime.now()),
                new MessageRow(UUID.randomUUID(), convId, 1L, "USER", "Test User", "a", OffsetDateTime.now())
        );
        when(msgRepo.findMessages(eq(convId), any(), anyInt())).thenReturn(batch);
        when(msgRepo.findAttachmentsByMessageIds(any())).thenReturn(List.of());

        SupportMessagePageDto page = service.listMessages(userId, null, 3);

        assertThat(page.messages()).hasSize(3);
        assertThat(page.nextBeforeSequence()).isEqualTo(1L);
    }

    @Test
    void listMessages_conversationNotFound_throws404() {
        when(convRepo.findByUserId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listMessages(userId, null, 25))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
    }

    // -------------------------------------------------------------------------
    // sendMessage
    // -------------------------------------------------------------------------

    @Test
    void sendMessage_noBodyNoFiles_throws400() {
        when(convRepo.findByUserId(userId)).thenReturn(Optional.of(conv("IDLE")));

        assertThatThrownBy(() -> service.sendMessage(userId, UUID.randomUUID(), null, List.of(), null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void sendMessage_textOnly_callsRpcAndReturnsDto() {
        UUID clientMsgId = UUID.randomUUID();
        when(convRepo.findByUserId(userId)).thenReturn(Optional.of(conv("IDLE")));
        MessageRow m = msg("hi");
        when(msgRepo.callAppendUserMessage(eq(userId), eq(clientMsgId), eq("hi"), anyString(), anyString()))
                .thenReturn(m);
        when(msgRepo.findAttachmentsByMessageId(m.id())).thenReturn(List.of());

        SupportMessageDto result = service.sendMessage(userId, clientMsgId, "hi", List.of(), null);

        assertThat(result.body()).isEqualTo("hi");
        assertThat(result.senderType()).isEqualTo("USER");
        verify(msgRepo).callAppendUserMessage(eq(userId), eq(clientMsgId), eq("hi"), anyString(), anyString());
    }

    @Test
    void sendMessage_idempotencyConflict_throws409() {
        UUID clientMsgId = UUID.randomUUID();
        when(convRepo.findByUserId(userId)).thenReturn(Optional.of(conv("IDLE")));
        PSQLException psql = new PSQLException(
                "Idempotency conflict: client message ID was reused with a different payload",
                PSQLState.UNKNOWN_STATE);
        when(msgRepo.callAppendUserMessage(any(), any(), any(), any(), any()))
                .thenThrow(new UncategorizedSQLException("task", "sql", psql));

        assertThatThrownBy(() -> service.sendMessage(userId, clientMsgId, "hi", List.of(), null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));
    }

    @Test
    void sendMessage_tooManyFiles_throws400() {
        when(convRepo.findByUserId(userId)).thenReturn(Optional.of(conv("IDLE")));
        List<MultipartFile> files = java.util.stream.Stream.generate(() -> mock(MultipartFile.class))
                .limit(11).toList();

        assertThatThrownBy(() -> service.sendMessage(userId, UUID.randomUUID(), "body", files, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void sendMessage_invalidContentType_throws400() {
        when(convRepo.findByUserId(userId)).thenReturn(Optional.of(conv("IDLE")));
        MultipartFile file = mock(MultipartFile.class);
        when(file.getContentType()).thenReturn("video/mp4");

        assertThatThrownBy(() -> service.sendMessage(userId, UUID.randomUUID(), "body", List.of(file), null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void sendMessage_rpcFails_cleansUpUploadedFiles() throws Exception {
        UUID clientMsgId = UUID.randomUUID();
        when(convRepo.findByUserId(userId)).thenReturn(Optional.of(conv("IDLE")));
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(1024L);
        when(file.getContentType()).thenReturn("image/jpeg");
        when(file.getOriginalFilename()).thenReturn("photo.jpg");
        when(file.getBytes()).thenReturn(new byte[1024]);

        PSQLException psql = new PSQLException("Support conversation not found", PSQLState.UNKNOWN_STATE);
        when(msgRepo.callAppendUserMessage(any(), any(), any(), any(), any()))
                .thenThrow(new UncategorizedSQLException("task", "sql", psql));

        assertThatThrownBy(() -> service.sendMessage(userId, clientMsgId, null, List.of(file), null))
                .isInstanceOf(ResponseStatusException.class);

        verify(storageService).deleteObject(eq("support-attachments"), anyString());
    }

    // -------------------------------------------------------------------------
    // markRead / close
    // -------------------------------------------------------------------------

    @Test
    void markRead_callsRpcOnConversation() {
        when(convRepo.findByUserId(userId)).thenReturn(Optional.of(conv("WAITING_STAFF")));

        service.markRead(userId, 5L);

        verify(convRepo).callMarkReadByUser(eq(convId), eq(userId), eq(5L));
    }

    @Test
    void close_callsRpc() {
        when(convRepo.findByUserId(userId)).thenReturn(Optional.of(conv("WAITING_STAFF")));

        service.close(userId);

        verify(convRepo).callCloseByUser(convId, userId);
    }

    // -------------------------------------------------------------------------
    // getAttachmentDownloadUrl
    // -------------------------------------------------------------------------

    @Test
    void getDownloadUrl_ownerUser_returnsSignedUrl() {
        UUID attachmentId = UUID.randomUUID();
        when(msgRepo.findConversationUserIdForAttachment(attachmentId)).thenReturn(userId);
        AttachmentRow att = new AttachmentRow(attachmentId, UUID.randomUUID(),
                "support-attachments", "support/path/file.jpg",
                "file.jpg", "image/jpeg", 1024L, "IMAGE", null, OffsetDateTime.now());
        when(msgRepo.findAttachmentWithConversationUserId(attachmentId)).thenReturn(Optional.of(att));
        when(storageService.generateSignedUrl("support-attachments", "support/path/file.jpg", 300))
                .thenReturn("https://storage.example.com/signed");

        String url = service.getAttachmentDownloadUrl(userId, attachmentId);

        assertThat(url).isEqualTo("https://storage.example.com/signed");
    }

    @Test
    void getDownloadUrl_notOwner_throws403() {
        UUID attachmentId = UUID.randomUUID();
        when(msgRepo.findConversationUserIdForAttachment(attachmentId)).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> service.getAttachmentDownloadUrl(userId, attachmentId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(403));
    }

    @Test
    void getDownloadUrl_attachmentNotFound_throws404() {
        UUID attachmentId = UUID.randomUUID();
        when(msgRepo.findConversationUserIdForAttachment(attachmentId)).thenReturn(null);

        assertThatThrownBy(() -> service.getAttachmentDownloadUrl(userId, attachmentId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
    }

    // -------------------------------------------------------------------------
    // sanitizeFileName utility
    // -------------------------------------------------------------------------

    @Test
    void sanitizeFileName_normalName_unchanged() {
        assertThat(SupportConversationService.sanitizeFileName("photo.jpg")).isEqualTo("photo.jpg");
    }

    @Test
    void sanitizeFileName_specialChars_replaced() {
        assertThat(SupportConversationService.sanitizeFileName("my file (1).jpg"))
                .isEqualTo("my_file__1_.jpg");
    }

    @Test
    void sanitizeFileName_null_returnsDefault() {
        assertThat(SupportConversationService.sanitizeFileName(null)).isEqualTo("attachment");
    }

    // -------------------------------------------------------------------------
    // Voice message tests
    // -------------------------------------------------------------------------

    @Test
    void sendMessage_voiceOnly_accepted() throws Exception {
        UUID clientMsgId = UUID.randomUUID();
        when(convRepo.findByUserId(userId)).thenReturn(Optional.of(conv("IDLE")));
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(1024L);
        when(file.getContentType()).thenReturn("audio/m4a");
        when(file.getOriginalFilename()).thenReturn("voice.m4a");
        when(file.getBytes()).thenReturn(new byte[1024]);

        MessageRow m = msg(null);
        when(msgRepo.callAppendUserMessage(eq(userId), eq(clientMsgId), any(), anyString(), anyString()))
                .thenReturn(m);
        when(msgRepo.findAttachmentsByMessageId(m.id())).thenReturn(List.of());

        SupportMessageDto result = service.sendMessage(userId, clientMsgId, null, List.of(file), List.of(5000L));
        assertThat(result).isNotNull();
        verify(storageService).uploadFile(eq("support-attachments"), anyString(), any(), eq("audio/m4a"));
    }

    @Test
    void sendMessage_voiceWithText_accepted() throws Exception {
        UUID clientMsgId = UUID.randomUUID();
        when(convRepo.findByUserId(userId)).thenReturn(Optional.of(conv("IDLE")));
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(2048L);
        when(file.getContentType()).thenReturn("audio/m4a");
        when(file.getOriginalFilename()).thenReturn("voice.m4a");
        when(file.getBytes()).thenReturn(new byte[2048]);

        MessageRow m = msg("Voice note with text");
        when(msgRepo.callAppendUserMessage(eq(userId), eq(clientMsgId), eq("Voice note with text"), anyString(), anyString()))
                .thenReturn(m);
        when(msgRepo.findAttachmentsByMessageId(m.id())).thenReturn(List.of());

        SupportMessageDto result = service.sendMessage(userId, clientMsgId, "Voice note with text", List.of(file), List.of(3000L));
        assertThat(result.body()).isEqualTo("Voice note with text");
    }

    @Test
    void sendMessage_unsupportedAudioType_throws400() {
        when(convRepo.findByUserId(userId)).thenReturn(Optional.of(conv("IDLE")));
        MultipartFile file = mock(MultipartFile.class);
        when(file.getContentType()).thenReturn("audio/ogg");
        when(file.getOriginalFilename()).thenReturn("voice.ogg");

        assertThatThrownBy(() -> service.sendMessage(userId, UUID.randomUUID(), null, List.of(file), List.of(1000L)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void sendMessage_oversizedVoiceFile_throws400() {
        when(convRepo.findByUserId(userId)).thenReturn(Optional.of(conv("IDLE")));
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(55L * 1024 * 1024);
        when(file.getContentType()).thenReturn("audio/m4a");
        when(file.getOriginalFilename()).thenReturn("voice.m4a");

        assertThatThrownBy(() -> service.sendMessage(userId, UUID.randomUUID(), null, List.of(file), List.of(1000L)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void sendMessage_overDurationVoice_throws400() {
        when(convRepo.findByUserId(userId)).thenReturn(Optional.of(conv("IDLE")));
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(1024L);
        when(file.getContentType()).thenReturn("audio/m4a");
        when(file.getOriginalFilename()).thenReturn("voice.m4a");

        assertThatThrownBy(() -> service.sendMessage(userId, UUID.randomUUID(), null, List.of(file), List.of(301_000L)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void sendMessage_voiceMissingDuration_throws400() {
        when(convRepo.findByUserId(userId)).thenReturn(Optional.of(conv("IDLE")));
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(1024L);
        when(file.getContentType()).thenReturn("audio/m4a");
        when(file.getOriginalFilename()).thenReturn("voice.m4a");

        assertThatThrownBy(() -> service.sendMessage(userId, UUID.randomUUID(), null, List.of(file), null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void sendMessage_m4aWithXm4aContentType_accepted() throws Exception {
        UUID clientMsgId = UUID.randomUUID();
        when(convRepo.findByUserId(userId)).thenReturn(Optional.of(conv("IDLE")));
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(1024L);
        when(file.getContentType()).thenReturn("audio/x-m4a");
        when(file.getOriginalFilename()).thenReturn("voice.m4a");
        when(file.getBytes()).thenReturn(new byte[1024]);

        MessageRow m = msg(null);
        when(msgRepo.callAppendUserMessage(eq(userId), eq(clientMsgId), any(), anyString(), anyString()))
                .thenReturn(m);
        when(msgRepo.findAttachmentsByMessageId(m.id())).thenReturn(List.of());

        service.sendMessage(userId, clientMsgId, null, List.of(file), List.of(2000L));
        verify(storageService).uploadFile(eq("support-attachments"), anyString(), any(), eq("audio/m4a"));
    }

    @Test
    void sendMessage_m4aWithMp4ContentType_normalized() throws Exception {
        UUID clientMsgId = UUID.randomUUID();
        when(convRepo.findByUserId(userId)).thenReturn(Optional.of(conv("IDLE")));
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(1024L);
        when(file.getContentType()).thenReturn("audio/mp4");
        when(file.getOriginalFilename()).thenReturn("voice.m4a");
        when(file.getBytes()).thenReturn(new byte[1024]);

        MessageRow m = msg(null);
        when(msgRepo.callAppendUserMessage(eq(userId), eq(clientMsgId), any(), anyString(), anyString()))
                .thenReturn(m);
        when(msgRepo.findAttachmentsByMessageId(m.id())).thenReturn(List.of());

        service.sendMessage(userId, clientMsgId, null, List.of(file), List.of(2000L));
        verify(storageService).uploadFile(eq("support-attachments"), anyString(), any(), eq("audio/m4a"));
    }

    @Test
    void sendMessage_voiceInvalidExtension_throws400() {
        when(convRepo.findByUserId(userId)).thenReturn(Optional.of(conv("IDLE")));
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(1024L);
        when(file.getContentType()).thenReturn("audio/m4a");
        when(file.getOriginalFilename()).thenReturn("voice.exe");

        assertThatThrownBy(() -> service.sendMessage(userId, UUID.randomUUID(), null, List.of(file), List.of(1000L)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void sendMessage_existingImageStillWorks() throws Exception {
        UUID clientMsgId = UUID.randomUUID();
        when(convRepo.findByUserId(userId)).thenReturn(Optional.of(conv("IDLE")));
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(1024L);
        when(file.getContentType()).thenReturn("image/jpeg");
        when(file.getOriginalFilename()).thenReturn("photo.jpg");
        when(file.getBytes()).thenReturn(new byte[1024]);

        MessageRow m = msg("check this");
        when(msgRepo.callAppendUserMessage(eq(userId), eq(clientMsgId), eq("check this"), anyString(), anyString()))
                .thenReturn(m);
        when(msgRepo.findAttachmentsByMessageId(m.id())).thenReturn(List.of());

        SupportMessageDto result = service.sendMessage(userId, clientMsgId, "check this", List.of(file), null);
        assertThat(result.body()).isEqualTo("check this");
        verify(storageService).uploadFile(eq("support-attachments"), anyString(), any(), eq("image/jpeg"));
    }

    @Test
    void toMessageDto_voiceAttachment_hasKindAndDuration() {
        MessageRow m = msg("voice msg");
        UUID attId = UUID.randomUUID();
        AttachmentRow att = new AttachmentRow(attId, m.id(),
                "support-attachments", "support/path/voice.m4a",
                "voice.m4a", "audio/m4a", 2048L, "VOICE", 18450L, OffsetDateTime.now());
        when(storageService.generateSignedUrl(anyString(), anyString(), anyInt()))
                .thenReturn("https://example.com/signed");

        SupportMessageDto dto = service.toMessageDto(m, List.of(att));

        assertThat(dto.attachments()).hasSize(1);
        assertThat(dto.attachments().get(0).attachmentKind()).isEqualTo("VOICE");
        assertThat(dto.attachments().get(0).durationMs()).isEqualTo(18450L);
    }

    @Test
    void normalizeAudioContentType_xM4A_normalizesToM4A() {
        assertThat(SupportConversationService.normalizeAudioContentType("audio/x-m4a", "voice.m4a"))
                .isEqualTo("audio/m4a");
    }

    @Test
    void normalizeAudioContentType_mp4WithM4aExt_normalizesToM4A() {
        assertThat(SupportConversationService.normalizeAudioContentType("audio/mp4", "voice.m4a"))
                .isEqualTo("audio/m4a");
    }

    @Test
    void normalizeAudioContentType_nonAudio_unchanged() {
        assertThat(SupportConversationService.normalizeAudioContentType("image/jpeg", "photo.jpg"))
                .isEqualTo("image/jpeg");
    }

    @Test
    void detectAttachmentKind_audio_isVoice() {
        assertThat(SupportConversationService.detectAttachmentKind("audio/m4a")).isEqualTo("VOICE");
    }

    @Test
    void detectAttachmentKind_image_isImage() {
        assertThat(SupportConversationService.detectAttachmentKind("image/jpeg")).isEqualTo("IMAGE");
    }

    @Test
    void detectAttachmentKind_pdf_isDocument() {
        assertThat(SupportConversationService.detectAttachmentKind("application/pdf")).isEqualTo("DOCUMENT");
    }

    @Test
    void detectAttachmentKind_text_isText() {
        assertThat(SupportConversationService.detectAttachmentKind("text/plain")).isEqualTo("TEXT");
    }

    @Test
    void detectAttachmentKind_unknown_isOther() {
        assertThat(SupportConversationService.detectAttachmentKind("application/octet-stream")).isEqualTo("OTHER");
    }

    @Test
    void parseDurations_validArray_returnsList() {
        List<Long> durations = SupportConversationService.parseDurations("[5000, 3000, null]");
        assertThat(durations).hasSize(3);
        assertThat(durations.get(0)).isEqualTo(5000L);
        assertThat(durations.get(1)).isEqualTo(3000L);
        assertThat(durations.get(2)).isNull();
    }

    @Test
    void parseDurations_null_returnsNull() {
        assertThat(SupportConversationService.parseDurations(null)).isNull();
    }

    @Test
    void parseDurations_blank_returnsNull() {
        assertThat(SupportConversationService.parseDurations("")).isNull();
    }

    @Test
    void parseDurations_invalidJson_throws400() {
        assertThatThrownBy(() -> SupportConversationService.parseDurations("not-json"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));
    }
}
