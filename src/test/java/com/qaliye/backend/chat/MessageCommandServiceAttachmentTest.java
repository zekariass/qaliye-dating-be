package com.qaliye.backend.chat;

import com.qaliye.backend.chat.config.ChatProperties;
import com.qaliye.backend.chat.dto.ChatMessageDto;
import com.qaliye.backend.chat.dto.SendMessageRequest;
import com.qaliye.backend.chat.exception.InvalidMessageException;
import com.qaliye.backend.chat.repository.ChatAttachmentRepository;
import com.qaliye.backend.chat.repository.ChatAttachmentRepository.AttachmentRow;
import com.qaliye.backend.chat.repository.ChatMatchRepository;
import com.qaliye.backend.chat.repository.ChatMessageRepository;
import com.qaliye.backend.chat.service.*;
import com.qaliye.backend.discovery.dto.UserPlanEntitlement;
import com.qaliye.backend.discovery.repository.DailyLimitRepository;
import com.qaliye.backend.discovery.service.PlanEntitlementService;
import com.qaliye.backend.notifications.service.NotificationOutboxService;
import com.qaliye.backend.storage.SupabaseStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageCommandServiceAttachmentTest {

    @Mock ChatMatchRepository matchRepository;
    @Mock ChatMessageRepository messageRepository;
    @Mock MatchAuthorizationService authorizationService;
    @Mock ChatOutboxService outboxService;
    @Mock ChatRateLimitService rateLimitService;
    @Mock ChatDtoMapper mapper;
    @Mock NotificationOutboxService notificationOutboxService;
    @Mock ChatAttachmentRepository attachmentRepository;
    @Mock SupabaseStorageService storageService;
    @Mock ChatProperties chatProperties;
    @Mock PlanEntitlementService entitlementService;
    @Mock DailyLimitRepository dailyLimitRepo;

    MessageCommandService service;

    UUID callerId   = UUID.randomUUID();
    UUID matchId    = UUID.randomUUID();
    UUID otherUser  = UUID.randomUUID();
    UUID clientMsgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new MessageCommandService(
                matchRepository, messageRepository, authorizationService,
                outboxService, rateLimitService, mapper, notificationOutboxService,
                attachmentRepository, storageService, chatProperties,
                entitlementService, dailyLimitRepo);
    }

    @Test
    void sendMessageWithAttachments_imageOnly_returns201() throws Exception {
        setupMocksForAttachmentSend();
        ChatProperties.Attachment attCfg = buildAttachmentConfig();
        when(chatProperties.getAttachment()).thenReturn(attCfg);

        MockMultipartFile image = new MockMultipartFile("files", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3});

        SendMessageRequest req = new SendMessageRequest();
        req.setClientMessageId(clientMsgId);
        req.setMessageType("TEXT");
        req.setBody(null);

        ChatMessageDto dto = buildDto();
        when(mapper.toMessageDto(any(), anyLong(), anyLong(), any(), anyList())).thenReturn(dto);

        MessageCommandService.SendResult result =
                service.sendMessageWithAttachments(callerId, matchId, req, List.of(image), null);

        assertThat(result.isNew()).isTrue();
        verify(storageService).uploadFile(eq("chat-attachments"), anyString(), any(byte[].class), eq("image/jpeg"));
        verify(attachmentRepository).insert(any(), eq("IMAGE"), eq("photo.jpg"), eq("image/jpeg"), anyLong(), anyString(), anyString(), isNull());
        verify(outboxService).createMessageCreatedEvent(any(), any(), anyLong(), any(), any(), isNull(), any(), anyList());
    }

    @Test
    void sendMessageWithAttachments_voiceOnly_returns201() throws Exception {
        setupMocksForAttachmentSend();
        ChatProperties.Attachment attCfg = buildAttachmentConfig();
        when(chatProperties.getAttachment()).thenReturn(attCfg);

        MockMultipartFile voice = new MockMultipartFile("files", "voice.m4a", "audio/m4a", new byte[]{1, 2, 3});

        SendMessageRequest req = new SendMessageRequest();
        req.setClientMessageId(clientMsgId);
        req.setMessageType("TEXT");
        req.setBody(null);

        ChatMessageDto dto = buildDto();
        when(mapper.toMessageDto(any(), anyLong(), anyLong(), any(), anyList())).thenReturn(dto);

        MessageCommandService.SendResult result =
                service.sendMessageWithAttachments(callerId, matchId, req, List.of(voice), List.of(5000L));

        assertThat(result.isNew()).isTrue();
        verify(storageService).uploadFile(eq("chat-attachments"), anyString(), any(byte[].class), eq("audio/m4a"));
        verify(attachmentRepository).insert(any(), eq("VOICE"), eq("voice.m4a"), eq("audio/m4a"), anyLong(), anyString(), anyString(), eq(5000L));
    }

    @Test
    void sendMessageWithAttachments_textWithImage_returns201() throws Exception {
        setupMocksForAttachmentSend();
        ChatProperties.Attachment attCfg = buildAttachmentConfig();
        when(chatProperties.getAttachment()).thenReturn(attCfg);

        MockMultipartFile image = new MockMultipartFile("files", "photo.png", "image/png", new byte[]{1, 2});

        SendMessageRequest req = new SendMessageRequest();
        req.setClientMessageId(clientMsgId);
        req.setMessageType("TEXT");
        req.setBody("Look at this!");

        ChatMessageDto dto = buildDto();
        when(mapper.toMessageDto(any(), anyLong(), anyLong(), any(), anyList())).thenReturn(dto);

        MessageCommandService.SendResult result =
                service.sendMessageWithAttachments(callerId, matchId, req, List.of(image), null);

        assertThat(result.isNew()).isTrue();
        verify(storageService).uploadFile(eq("chat-attachments"), anyString(), any(byte[].class), eq("image/png"));
    }

    @Test
    void sendMessageWithAttachments_unsupportedContentType_throwsInvalid() {
        setupMinimalMatchMock();
        ChatProperties.Attachment attCfg = buildAttachmentConfig();
        when(chatProperties.getAttachment()).thenReturn(attCfg);

        MockMultipartFile video = new MockMultipartFile("files", "video.mp4", "video/mp4", new byte[]{1, 2});

        SendMessageRequest req = new SendMessageRequest();
        req.setClientMessageId(clientMsgId);
        req.setMessageType("TEXT");
        req.setBody(null);

        assertThatThrownBy(() -> service.sendMessageWithAttachments(callerId, matchId, req, List.of(video), null))
                .isInstanceOf(InvalidMessageException.class);
    }

    @Test
    void sendMessageWithAttachments_oversizedImage_throwsInvalid() {
        setupMinimalMatchMock();
        ChatProperties.Attachment attCfg = buildAttachmentConfig();
        when(chatProperties.getAttachment()).thenReturn(attCfg);

        byte[] big = new byte[(int) (attCfg.getImageMaxFileSizeBytes() + 1)];
        MockMultipartFile image = new MockMultipartFile("files", "big.jpg", "image/jpeg", big);

        SendMessageRequest req = new SendMessageRequest();
        req.setClientMessageId(clientMsgId);
        req.setMessageType("TEXT");
        req.setBody(null);

        assertThatThrownBy(() -> service.sendMessageWithAttachments(callerId, matchId, req, List.of(image), null))
                .isInstanceOf(InvalidMessageException.class);
    }

    @Test
    void sendMessageWithAttachments_voiceMissingDuration_throwsInvalid() {
        setupMinimalMatchMock();
        ChatProperties.Attachment attCfg = buildAttachmentConfig();
        when(chatProperties.getAttachment()).thenReturn(attCfg);

        MockMultipartFile voice = new MockMultipartFile("files", "voice.m4a", "audio/m4a", new byte[]{1, 2});

        SendMessageRequest req = new SendMessageRequest();
        req.setClientMessageId(clientMsgId);
        req.setMessageType("TEXT");
        req.setBody(null);

        assertThatThrownBy(() -> service.sendMessageWithAttachments(callerId, matchId, req, List.of(voice), null))
                .isInstanceOf(InvalidMessageException.class);
    }

    @Test
    void sendMessageWithAttachments_voiceDurationExceedsLimit_throwsInvalid() {
        setupMinimalMatchMock();
        ChatProperties.Attachment attCfg = buildAttachmentConfig();
        when(chatProperties.getAttachment()).thenReturn(attCfg);

        MockMultipartFile voice = new MockMultipartFile("files", "voice.m4a", "audio/m4a", new byte[]{1, 2});

        SendMessageRequest req = new SendMessageRequest();
        req.setClientMessageId(clientMsgId);
        req.setMessageType("TEXT");
        req.setBody(null);

        long overLimit = (attCfg.getVoiceMaxDurationSeconds() + 1) * 1000L;
        assertThatThrownBy(() -> service.sendMessageWithAttachments(callerId, matchId, req, List.of(voice), List.of(overLimit)))
                .isInstanceOf(InvalidMessageException.class);
    }

    @Test
    void sendMessageWithAttachments_emptyMessageNoFiles_throwsInvalid() {
        SendMessageRequest req = new SendMessageRequest();
        req.setClientMessageId(clientMsgId);
        req.setMessageType("TEXT");
        req.setBody(null);

        assertThatThrownBy(() -> service.sendMessageWithAttachments(callerId, matchId, req, null, null))
                .isInstanceOf(InvalidMessageException.class);
    }

    @Test
    void sendMessageWithAttachments_tooManyImages_throwsInvalid() {
        setupMinimalMatchMock();
        ChatProperties.Attachment attCfg = buildAttachmentConfig();
        when(chatProperties.getAttachment()).thenReturn(attCfg);

        MockMultipartFile img1 = new MockMultipartFile("files", "a.jpg", "image/jpeg", new byte[]{1});
        MockMultipartFile img2 = new MockMultipartFile("files", "b.jpg", "image/jpeg", new byte[]{1});
        MockMultipartFile img3 = new MockMultipartFile("files", "c.jpg", "image/jpeg", new byte[]{1});
        MockMultipartFile img4 = new MockMultipartFile("files", "d.jpg", "image/jpeg", new byte[]{1});
        MockMultipartFile img5 = new MockMultipartFile("files", "e.jpg", "image/jpeg", new byte[]{1});
        MockMultipartFile img6 = new MockMultipartFile("files", "f.jpg", "image/jpeg", new byte[]{1});

        SendMessageRequest req = new SendMessageRequest();
        req.setClientMessageId(clientMsgId);
        req.setMessageType("TEXT");
        req.setBody(null);

        assertThatThrownBy(() -> service.sendMessageWithAttachments(callerId, matchId, req,
                List.of(img1, img2, img3, img4, img5, img6), null))
                .isInstanceOf(InvalidMessageException.class);
    }

    @Test
    void sendMessageWithAttachments_idempotentRetry_returnsExisting() {
        ChatMessageRepository.MessageRow existing = buildMessageRow(1L);
        when(messageRepository.findByIdempotencyKey(callerId, clientMsgId))
                .thenReturn(Optional.of(existing));
        when(attachmentRepository.findByMessageIds(any()))
                .thenReturn(Map.of());

        ChatMessageDto dto = buildDto();
        when(mapper.toMessageDto(any(), anyLong(), anyLong(), any(), anyList())).thenReturn(dto);

        SendMessageRequest req = new SendMessageRequest();
        req.setClientMessageId(clientMsgId);
        req.setMessageType("TEXT");
        req.setBody("photo message");

        MessageCommandService.SendResult result =
                service.sendMessageWithAttachments(callerId, matchId, req, null, null);

        assertThat(result.isNew()).isFalse();
        verifyNoInteractions(storageService, outboxService, rateLimitService, notificationOutboxService);
    }

    @Test
    void sendMessageWithAttachments_storageFailure_rollsBackAndThrows() throws Exception {
        setupMocksForAttachmentSend();
        ChatProperties.Attachment attCfg = buildAttachmentConfig();
        when(chatProperties.getAttachment()).thenReturn(attCfg);

        MockMultipartFile image = new MockMultipartFile("files", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3});

        doThrow(new RuntimeException("Storage error"))
                .when(storageService).uploadFile(anyString(), anyString(), any(byte[].class), anyString());

        SendMessageRequest req = new SendMessageRequest();
        req.setClientMessageId(clientMsgId);
        req.setMessageType("TEXT");
        req.setBody(null);

        assertThatThrownBy(() -> service.sendMessageWithAttachments(callerId, matchId, req, List.of(image), null))
                .isInstanceOf(InvalidMessageException.class);
    }

    private void setupMocksForAttachmentSend() {
        ChatMatchRepository.MatchRow match = buildActiveMatch();
        ChatMessageRepository.MessageRow inserted = buildMessageRow(1L);

        when(messageRepository.findByIdempotencyKey(callerId, clientMsgId)).thenReturn(Optional.empty());
        when(matchRepository.findByIdForUpdate(matchId)).thenReturn(Optional.of(match));
        when(matchRepository.reserveAndIncrementSequence(matchId)).thenReturn(1L);
        when(messageRepository.insert(any(), any(), any(), anyString(), any(), anyLong()))
                .thenReturn(inserted);

        AttachmentRow attRow = new AttachmentRow(
                UUID.randomUUID(), inserted.id(), "IMAGE", "photo.jpg", "image/jpeg",
                3, "chat-attachments", "some/path", null, OffsetDateTime.now());
        lenient().when(attachmentRepository.insert(any(), anyString(), anyString(), anyString(), anyLong(), anyString(), anyString(), any()))
                .thenReturn(attRow);

        lenient().when(entitlementService.loadEntitlement(callerId)).thenReturn(
                new UserPlanEntitlement(callerId, "PREMIUM", true, 150, 5, 10, null, null, 0, 0));
        lenient().when(dailyLimitRepo.lockForUpdate(callerId)).thenReturn(
                Optional.of(new DailyLimitRepository.DailyLimitRow(callerId, 0, 0, 0, 0, 0)));
    }

    private void setupMinimalMatchMock() {
        ChatMatchRepository.MatchRow match = buildActiveMatch();
        when(messageRepository.findByIdempotencyKey(callerId, clientMsgId)).thenReturn(Optional.empty());
        when(matchRepository.findByIdForUpdate(matchId)).thenReturn(Optional.of(match));
    }

    private ChatProperties.Attachment buildAttachmentConfig() {
        ChatProperties.Attachment cfg = new ChatProperties.Attachment();
        return cfg;
    }

    private ChatMatchRepository.MatchRow buildActiveMatch() {
        return new ChatMatchRepository.MatchRow(matchId, callerId, otherUser, "ACTIVE",
                null, null, null, 2L, 0L, 0L, 0L, 0L, null, null, null, null, null, null, 0L, 0L, null, null);
    }

    private ChatMessageRepository.MessageRow buildMessageRow(long seq) {
        return new ChatMessageRepository.MessageRow(UUID.randomUUID(), matchId, seq, callerId,
                "TEXT", "photo message", "APPROVED", OffsetDateTime.now(), null);
    }

    private ChatMessageDto buildDto() {
        return new ChatMessageDto(UUID.randomUUID(), matchId, 1L,
                callerId, "TEXT", null, "SENT", OffsetDateTime.now().toInstant(),
                List.of());
    }
}
