package com.qaliye.backend.chat;

import com.qaliye.backend.billing.repository.ActionLimitRepository;
import com.qaliye.backend.billing.repository.MessagePairTrackerRepository;
import com.qaliye.backend.billing.service.ActionCostService;
import com.qaliye.backend.billing.service.CreditService;
import com.qaliye.backend.chat.config.ChatProperties;
import com.qaliye.backend.chat.dto.ChatMessageDto;
import com.qaliye.backend.chat.dto.SendMessageRequest;
import com.qaliye.backend.chat.repository.ChatAttachmentRepository;
import com.qaliye.backend.chat.repository.ChatAttachmentRepository.AttachmentRow;
import com.qaliye.backend.chat.repository.ChatMatchRepository;
import com.qaliye.backend.chat.repository.ChatMessageRepository;
import com.qaliye.backend.chat.service.*;
import com.qaliye.backend.discovery.exception.ActionLimitExceededException;
import com.qaliye.backend.notifications.service.NotificationOutboxService;
import com.qaliye.backend.storage.SupabaseStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for per-recipient, period-aware message limit enforcement in
 * MessageCommandService. Covers LIFETIME pair tracking and the non-LIFETIME
 * fallback to the existing per-user period tracker.
 */
@ExtendWith(MockitoExtension.class)
class MessagePairLimitTest {

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
    @Mock ActionCostService actionCostService;
    @Mock ActionLimitRepository actionLimitRepo;
    @Mock CreditService creditService;
    @Mock MessagePairTrackerRepository pairTrackerRepo;

    MessageCommandService service;

    UUID callerId    = UUID.randomUUID();
    UUID recipientId = UUID.randomUUID();
    UUID matchId     = UUID.randomUUID();
    UUID clientMsgId = UUID.randomUUID();
    UUID ruleId      = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new MessageCommandService(
                matchRepository, messageRepository, authorizationService,
                outboxService, rateLimitService, mapper, notificationOutboxService,
                attachmentRepository, storageService, chatProperties,
                actionCostService, actionLimitRepo, creditService, pairTrackerRepo);
        stubMessageInsert();
        stubMapper();
    }

    // =========================================================================
    // TEXT MESSAGE — LIFETIME tracking
    // =========================================================================

    @Test
    void sendMessage_lifetimeLimit_withinAllowance_noCreditsCharged() {
        stubMatch();
        stubPlanRuleConfig("MESSAGE", lifetimeConfig(50, 2, true));
        stubPairIncrement(true); // within limit

        sendTextMessage();

        verify(pairTrackerRepo).ensureLifetimePairExists(callerId, recipientId, ruleId);
        verify(pairTrackerRepo).tryIncrementLifetimeByUnderLimit(callerId, recipientId, ruleId, 50, 1);
        verify(creditService, never()).consumeCredits(any(), anyLong(), any(), any());
    }

    @Test
    void sendMessage_lifetimeLimit_pastLimit_applyCreditAfterLimit_chargesCredits() {
        stubMatch();
        stubPlanRuleConfig("MESSAGE", lifetimeConfig(50, 3, true));
        stubPairIncrement(false); // limit exhausted

        sendTextMessage();

        verify(pairTrackerRepo).incrementLifetimeBy(callerId, recipientId, ruleId, 1);
        verify(creditService).consumeCredits(eq(callerId), eq(3L), eq("MESSAGE"), anyString());
    }

    @Test
    void sendMessage_lifetimeLimit_pastLimit_zeroCost_noCreditsCharged() {
        stubMatch();
        stubPlanRuleConfig("MESSAGE", lifetimeConfig(50, 0, true));
        stubPairIncrement(false); // limit exhausted

        sendTextMessage();

        verify(pairTrackerRepo).incrementLifetimeBy(callerId, recipientId, ruleId, 1);
        verify(creditService, never()).consumeCredits(any(), anyLong(), any(), any());
    }

    @Test
    void sendMessage_lifetimeLimit_pastLimit_blocked_throwsException() {
        stubMatch();
        stubPlanRuleConfig("MESSAGE", lifetimeConfig(50, 2, false)); // blocked after limit
        stubPairIncrement(false); // limit exhausted

        assertThatThrownBy(this::sendTextMessage)
                .isInstanceOf(ActionLimitExceededException.class)
                .extracting("actionType").isEqualTo("MESSAGE");

        verify(pairTrackerRepo, never()).incrementLifetimeBy(any(), any(), any(), anyInt());
        verify(creditService, never()).consumeCredits(any(), anyLong(), any(), any());
    }

    @Test
    void sendMessage_lifetimeLimit_nullLimitValue_unlimited_memberCostApplied() {
        // null limitValue = unlimited allowance; member cost is the applicable cost
        stubMatch();
        stubPlanRuleConfig("MESSAGE",
                new ActionCostService.PlanRuleConfig(ruleId, 2L, 5L, null, "LIFETIME", true));
        // No pairTrackerRepo calls expected — unlimited means just pay member cost

        sendTextMessage();

        verify(pairTrackerRepo, never()).ensureLifetimePairExists(any(), any(), any());
        verify(creditService).consumeCredits(eq(callerId), eq(2L), eq("MESSAGE"), anyString());
    }

    @Test
    void sendMessage_lifetimeLimit_nullLimitValue_zeroCost_noCreditsCharged() {
        stubMatch();
        stubPlanRuleConfig("MESSAGE",
                new ActionCostService.PlanRuleConfig(ruleId, 0L, 0L, null, "LIFETIME", true));

        sendTextMessage();

        verify(pairTrackerRepo, never()).ensureLifetimePairExists(any(), any(), any());
        verify(creditService, never()).consumeCredits(any(), anyLong(), any(), any());
    }

    @Test
    void sendMessage_noRuleConfigured_free() {
        stubMatch();
        // ruleId = null → no rule → always free
        stubPlanRuleConfig("MESSAGE",
                new ActionCostService.PlanRuleConfig(null, 0, 0, null, "DAY", false));

        sendTextMessage();

        verifyNoInteractions(pairTrackerRepo, actionLimitRepo);
        verify(creditService, never()).consumeCredits(any(), anyLong(), any(), any());
    }

    // =========================================================================
    // RECIPIENT INDEPENDENCE — core business requirement
    // =========================================================================

    @Test
    void sendMessage_differentRecipients_haveIndependentLifetimeAllowances() {
        UUID recipientB = UUID.randomUUID();
        UUID matchIdB   = UUID.randomUUID();
        UUID msgIdB     = UUID.randomUUID();

        stubPlanRuleConfig("MESSAGE", lifetimeConfig(50, 2, true));

        // Recipient A: limit exhausted
        ChatMatchRepository.MatchRow matchA = buildMatch(matchId, callerId, recipientId);
        stubMessageInsertForClient(clientMsgId);
        when(matchRepository.findByIdForUpdate(matchId)).thenReturn(Optional.of(matchA));
        when(pairTrackerRepo.tryIncrementLifetimeByUnderLimit(callerId, recipientId, ruleId, 50, 1))
                .thenReturn(Optional.empty()); // exhausted for A
        when(mapper.toMessageDto(any(), anyLong(), anyLong(), any())).thenReturn(buildDto());

        SendMessageRequest reqA = buildRequest(clientMsgId);
        service.sendMessage(callerId, matchId, reqA);
        verify(creditService).consumeCredits(eq(callerId), eq(2L), eq("MESSAGE"), anyString());

        // Recipient B: fresh allowance, no credits
        ChatMatchRepository.MatchRow matchB = buildMatch(matchIdB, callerId, recipientB);
        when(matchRepository.findByIdForUpdate(matchIdB)).thenReturn(Optional.of(matchB));
        when(pairTrackerRepo.tryIncrementLifetimeByUnderLimit(callerId, recipientB, ruleId, 50, 1))
                .thenReturn(Optional.of(1)); // within limit for B
        UUID msgIdBUuid = UUID.randomUUID();
        when(messageRepository.findByIdempotencyKey(callerId, msgIdBUuid)).thenReturn(Optional.empty());
        when(messageRepository.insert(eq(matchIdB), any(), eq(msgIdBUuid), any(), any(), anyLong()))
                .thenReturn(buildMsgRow(matchIdB, msgIdBUuid));
        when(matchRepository.reserveAndIncrementSequence(matchIdB)).thenReturn(2L);
        when(mapper.toMessageDto(any(), anyLong(), anyLong(), any())).thenReturn(buildDto());

        SendMessageRequest reqB = buildRequest(msgIdBUuid);
        service.sendMessage(callerId, matchIdB, reqB);

        // Only one credit charge (from A's exhausted limit); B gets free messages
        verify(creditService, times(1)).consumeCredits(any(), anyLong(), any(), any());
    }

    // =========================================================================
    // NON-LIFETIME — falls back to per-user period tracker
    // =========================================================================

    @Test
    void sendMessage_dayPeriod_usesExistingPerUserTracker() {
        stubMatch();
        UUID dayRuleId = UUID.randomUUID();
        LocalDate today = LocalDate.now();

        // getPlanRuleConfig returns DAY period → routes to evaluate()
        when(actionCostService.getPlanRuleConfig(callerId, "MESSAGE"))
                .thenReturn(new ActionCostService.PlanRuleConfig(dayRuleId, 0, 0, 10, "DAY", false));
        // evaluate() says within limit, free
        when(actionCostService.evaluate(callerId, "MESSAGE"))
                .thenReturn(new ActionCostService.ActionCostResult(
                        dayRuleId, 0, true, false, false, today, today, 3, 10, "DAY"));
        when(actionLimitRepo.tryIncrementUnderLimit(callerId, dayRuleId, today, 10))
                .thenReturn(Optional.of(4));

        sendTextMessage();

        verify(actionLimitRepo).ensureExists(callerId, dayRuleId, today, today);
        verify(actionLimitRepo).tryIncrementUnderLimit(callerId, dayRuleId, today, 10);
        verifyNoInteractions(pairTrackerRepo);
        verify(creditService, never()).consumeCredits(any(), anyLong(), any(), any());
    }

    @Test
    void sendMessage_dayPeriod_limitExhausted_blocked() {
        stubMatch();
        UUID dayRuleId = UUID.randomUUID();
        LocalDate today = LocalDate.now();

        when(actionCostService.getPlanRuleConfig(callerId, "MESSAGE"))
                .thenReturn(new ActionCostService.PlanRuleConfig(dayRuleId, 0, 0, 10, "DAY", false));
        // evaluate() says blocked
        when(actionCostService.evaluate(callerId, "MESSAGE"))
                .thenReturn(new ActionCostService.ActionCostResult(
                        dayRuleId, 0, false, true, true, today, today, 10, 10, "DAY"));

        assertThatThrownBy(this::sendTextMessage)
                .isInstanceOf(ActionLimitExceededException.class);

        verifyNoInteractions(pairTrackerRepo);
    }

    @Test
    void sendMessage_dayPeriod_limitExhausted_chargesCredits() {
        stubMatch();
        UUID dayRuleId = UUID.randomUUID();
        LocalDate today = LocalDate.now();

        when(actionCostService.getPlanRuleConfig(callerId, "MESSAGE"))
                .thenReturn(new ActionCostService.PlanRuleConfig(dayRuleId, 0, 5, 10, "DAY", true));
        when(actionCostService.evaluate(callerId, "MESSAGE"))
                .thenReturn(new ActionCostService.ActionCostResult(
                        dayRuleId, 5, false, true, false, today, today, 10, 10, "DAY"));

        sendTextMessage();

        verify(creditService).consumeCredits(eq(callerId), eq(5L), eq("MESSAGE"), anyString());
        verifyNoInteractions(pairTrackerRepo);
    }

    // =========================================================================
    // ATTACHMENT MESSAGES — LIFETIME tracking for VOICE + IMAGE (independent)
    // =========================================================================

    @Test
    void sendMessageWithAttachments_voiceLifetime_withinLimit_free() throws Exception {
        UUID voiceRuleId = UUID.randomUUID();
        setupAttachmentMocks();

        when(actionCostService.getPlanRuleConfig(callerId, "VOICE_MESSAGE"))
                .thenReturn(new ActionCostService.PlanRuleConfig(voiceRuleId, 0, 0, 20, "LIFETIME", true));

        when(pairTrackerRepo.tryIncrementLifetimeByUnderLimit(callerId, recipientId, voiceRuleId, 20, 1))
                .thenReturn(Optional.of(1)); // within voice limit

        MockMultipartFile voice = new MockMultipartFile("files", "voice.m4a", "audio/m4a", new byte[]{1});
        service.sendMessageWithAttachments(callerId, matchId,
                buildRequest(clientMsgId), List.of(voice), List.of(5000L));

        verify(creditService, never()).consumeCredits(any(), anyLong(), any(), any());
    }

    @Test
    void sendMessageWithAttachments_voiceLifetime_pastLimit_chargesVoiceCost() throws Exception {
        UUID voiceRuleId = UUID.randomUUID();
        setupAttachmentMocks();

        when(actionCostService.getPlanRuleConfig(callerId, "VOICE_MESSAGE"))
                .thenReturn(new ActionCostService.PlanRuleConfig(voiceRuleId, 0, 5, 20, "LIFETIME", true));

        when(pairTrackerRepo.tryIncrementLifetimeByUnderLimit(callerId, recipientId, voiceRuleId, 20, 1))
                .thenReturn(Optional.empty()); // voice limit exhausted

        MockMultipartFile voice = new MockMultipartFile("files", "v.m4a", "audio/m4a", new byte[]{1});
        service.sendMessageWithAttachments(callerId, matchId,
                buildRequest(clientMsgId), List.of(voice), List.of(3000L));

        verify(pairTrackerRepo).incrementLifetimeBy(callerId, recipientId, voiceRuleId, 1);
        verify(creditService).consumeCredits(eq(callerId), eq(5L), eq("VOICE_MESSAGE"), anyString());
    }

    @Test
    void sendMessageWithAttachments_imageLifetime_pastLimit_highestCostWins() throws Exception {
        UUID imageRuleId = UUID.randomUUID();
        setupAttachmentMocks();

        // IMAGE over limit at 4 credits each × 2 images = 8
        when(actionCostService.getPlanRuleConfig(callerId, "IMAGE_MESSAGE"))
                .thenReturn(new ActionCostService.PlanRuleConfig(imageRuleId, 0, 4, 10, "LIFETIME", true));

        when(pairTrackerRepo.tryIncrementLifetimeByUnderLimit(callerId, recipientId, imageRuleId, 10, 2))
                .thenReturn(Optional.empty()); // image limit exhausted

        MockMultipartFile img1 = new MockMultipartFile("files", "a.jpg", "image/jpeg", new byte[]{1});
        MockMultipartFile img2 = new MockMultipartFile("files", "b.jpg", "image/jpeg", new byte[]{2});
        service.sendMessageWithAttachments(callerId, matchId,
                buildRequest(clientMsgId), List.of(img1, img2), null);

        verify(pairTrackerRepo).incrementLifetimeBy(callerId, recipientId, imageRuleId, 2);
        // 4 credits × 2 images = 8, charged as IMAGE_MESSAGE
        verify(creditService).consumeCredits(eq(callerId), eq(8L), eq("IMAGE_MESSAGE"), anyString());
    }

    @Test
    void sendMessageWithAttachments_voiceAndImage_bothPastLimit_highestCostWins() throws Exception {
        UUID voiceRuleId = UUID.randomUUID();
        UUID imageRuleId = UUID.randomUUID();
        setupAttachmentMocks();

        // Voice past limit at 2 credits × 1 = 2; image past limit at 4 credits × 1 = 4
        when(actionCostService.getPlanRuleConfig(callerId, "VOICE_MESSAGE"))
                .thenReturn(new ActionCostService.PlanRuleConfig(voiceRuleId, 0, 2, 5, "LIFETIME", true));
        when(actionCostService.getPlanRuleConfig(callerId, "IMAGE_MESSAGE"))
                .thenReturn(new ActionCostService.PlanRuleConfig(imageRuleId, 0, 4, 5, "LIFETIME", true));

        when(pairTrackerRepo.tryIncrementLifetimeByUnderLimit(callerId, recipientId, voiceRuleId, 5, 1))
                .thenReturn(Optional.empty()); // voice exhausted
        when(pairTrackerRepo.tryIncrementLifetimeByUnderLimit(callerId, recipientId, imageRuleId, 5, 1))
                .thenReturn(Optional.empty()); // image exhausted

        MockMultipartFile voice = new MockMultipartFile("files", "v.m4a", "audio/m4a", new byte[]{1});
        MockMultipartFile img = new MockMultipartFile("files", "a.jpg", "image/jpeg", new byte[]{2});
        service.sendMessageWithAttachments(callerId, matchId,
                buildRequest(clientMsgId), List.of(voice, img), List.of(1000L));

        // voice=2×1=2, image=4×1=4 → image cost wins
        verify(creditService).consumeCredits(eq(callerId), eq(4L), eq("IMAGE_MESSAGE"), anyString());
    }

    @Test
    void sendMessageWithAttachments_voiceLifetime_blocked_throwsException() throws Exception {
        UUID voiceRuleId = UUID.randomUUID();
        setupAttachmentMocks();

        when(actionCostService.getPlanRuleConfig(callerId, "VOICE_MESSAGE"))
                .thenReturn(new ActionCostService.PlanRuleConfig(voiceRuleId, 0, 5, 10, "LIFETIME", false));

        when(pairTrackerRepo.tryIncrementLifetimeByUnderLimit(callerId, recipientId, voiceRuleId, 10, 1))
                .thenReturn(Optional.empty()); // voice exhausted + blocked

        MockMultipartFile voice = new MockMultipartFile("files", "v.m4a", "audio/m4a", new byte[]{1});
        assertThatThrownBy(() ->
                service.sendMessageWithAttachments(callerId, matchId,
                        buildRequest(clientMsgId), List.of(voice), List.of(1000L)))
                .isInstanceOf(ActionLimitExceededException.class)
                .extracting("actionType").isEqualTo("VOICE_MESSAGE");

        verify(creditService, never()).consumeCredits(any(), anyLong(), any(), any());
    }

    @Test
    void sendMessageWithAttachments_imageLifetime_pastLimit_blocked() throws Exception {
        UUID imageRuleId = UUID.randomUUID();
        setupAttachmentMocks();

        when(actionCostService.getPlanRuleConfig(callerId, "IMAGE_MESSAGE"))
                .thenReturn(new ActionCostService.PlanRuleConfig(imageRuleId, 0, 3, 5, "LIFETIME", false));

        when(pairTrackerRepo.tryIncrementLifetimeByUnderLimit(callerId, recipientId, imageRuleId, 5, 1))
                .thenReturn(Optional.empty());

        MockMultipartFile img = new MockMultipartFile("files", "a.jpg", "image/jpeg", new byte[]{1});
        assertThatThrownBy(() ->
                service.sendMessageWithAttachments(callerId, matchId,
                        buildRequest(clientMsgId), List.of(img), null))
                .isInstanceOf(ActionLimitExceededException.class)
                .extracting("actionType").isEqualTo("IMAGE_MESSAGE");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private ActionCostService.PlanRuleConfig lifetimeConfig(int limit, long actualCost, boolean applyAfter) {
        return new ActionCostService.PlanRuleConfig(ruleId, 0, actualCost, limit, "LIFETIME", applyAfter);
    }

    private void stubPlanRuleConfig(String actionCode, ActionCostService.PlanRuleConfig config) {
        when(actionCostService.getPlanRuleConfig(callerId, actionCode)).thenReturn(config);
    }

    private void stubPairIncrement(boolean withinLimit) {
        if (withinLimit) {
            when(pairTrackerRepo.tryIncrementLifetimeByUnderLimit(any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(Optional.of(1));
        } else {
            when(pairTrackerRepo.tryIncrementLifetimeByUnderLimit(any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(Optional.empty());
        }
    }

    private void stubMatch() {
        ChatMatchRepository.MatchRow match = buildMatch(matchId, callerId, recipientId);
        when(messageRepository.findByIdempotencyKey(callerId, clientMsgId)).thenReturn(Optional.empty());
        when(matchRepository.findByIdForUpdate(matchId)).thenReturn(Optional.of(match));
    }

    private void stubMessageInsert() {
        lenient().when(matchRepository.reserveAndIncrementSequence(matchId)).thenReturn(1L);
        lenient().when(messageRepository.insert(any(), any(), any(), any(), any(), anyLong()))
                .thenReturn(buildMsgRow(matchId, clientMsgId));
    }

    private void stubMessageInsertForClient(UUID msgId) {
        when(messageRepository.findByIdempotencyKey(callerId, msgId)).thenReturn(Optional.empty());
        when(messageRepository.insert(eq(matchId), any(), eq(msgId), any(), any(), anyLong()))
                .thenReturn(buildMsgRow(matchId, msgId));
        when(matchRepository.reserveAndIncrementSequence(matchId)).thenReturn(1L);
    }

    private void stubMapper() {
        lenient().when(mapper.toMessageDto(any(), anyLong(), anyLong(), any())).thenReturn(buildDto());
        lenient().when(mapper.toMessageDto(any(), anyLong(), anyLong(), any(), anyList())).thenReturn(buildDto());
    }

    private void setupAttachmentMocks() {
        ChatMatchRepository.MatchRow match = buildMatch(matchId, callerId, recipientId);
        when(messageRepository.findByIdempotencyKey(callerId, clientMsgId)).thenReturn(Optional.empty());
        when(matchRepository.findByIdForUpdate(matchId)).thenReturn(Optional.of(match));
        lenient().when(matchRepository.reserveAndIncrementSequence(matchId)).thenReturn(1L);
        lenient().when(messageRepository.insert(any(), any(), any(), any(), any(), anyLong()))
                .thenReturn(buildMsgRow(matchId, clientMsgId));
        AttachmentRow attRow = new AttachmentRow(UUID.randomUUID(), UUID.randomUUID(),
                "IMAGE", "f.jpg", "image/jpeg", 3, "chat-attachments", "p", null, OffsetDateTime.now());
        lenient().when(attachmentRepository.insert(any(), anyString(), anyString(), anyString(),
                anyLong(), anyString(), anyString(), any())).thenReturn(attRow);
        ChatProperties.Attachment cfg = new ChatProperties.Attachment();
        lenient().when(chatProperties.getAttachment()).thenReturn(cfg);
    }

    private void sendTextMessage() {
        service.sendMessage(callerId, matchId, buildRequest(clientMsgId));
    }

    private SendMessageRequest buildRequest(UUID msgId) {
        SendMessageRequest req = new SendMessageRequest();
        req.setClientMessageId(msgId);
        req.setMessageType("TEXT");
        req.setBody("Hello");
        return req;
    }

    private ChatMatchRepository.MatchRow buildMatch(UUID mId, UUID u1, UUID u2) {
        return new ChatMatchRepository.MatchRow(mId, u1, u2, "ACTIVE",
                null, null, null, 2L, 0L, 0L, 0L, 0L,
                null, null, null, null, null, null, 0L, 0L, null, null);
    }

    private ChatMessageRepository.MessageRow buildMsgRow(UUID mId, UUID msgId) {
        return new ChatMessageRepository.MessageRow(msgId, mId, 1L, callerId,
                "TEXT", "Hello", "APPROVED", OffsetDateTime.now(), null);
    }

    private ChatMessageDto buildDto() {
        return new ChatMessageDto(UUID.randomUUID(), matchId, 1L,
                callerId, "TEXT", "Hello", "SENT",
                OffsetDateTime.now().toInstant(), List.of());
    }
}
