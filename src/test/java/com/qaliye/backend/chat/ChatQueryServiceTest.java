package com.qaliye.backend.chat;

import com.qaliye.backend.activity.ActivityStatusService;
import com.qaliye.backend.chat.cursor.ChatCursorCodec;
import com.qaliye.backend.chat.repository.ChatMatchRepository;
import com.qaliye.backend.chat.repository.ChatMessageRepository;
import com.qaliye.backend.chat.repository.ChatNotificationSettingsRepository;
import com.qaliye.backend.chat.service.ChatDtoMapper;
import com.qaliye.backend.chat.service.ChatQueryService;
import com.qaliye.backend.chat.service.MatchAuthorizationService;
import com.qaliye.backend.discovery.service.StorageSigningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatQueryServiceTest {

    @Mock MatchAuthorizationService authorizationService;
    @Mock ChatMatchRepository matchRepository;
    @Mock ChatMessageRepository messageRepository;
    @Mock ChatNotificationSettingsRepository notifSettingsRepo;
    @Mock ChatCursorCodec cursorCodec;
    @Mock ChatDtoMapper mapper;
    @Mock StorageSigningService signingService;
    @Mock NamedParameterJdbcTemplate jdbc;
    @Mock ActivityStatusService activityStatusService;

    ChatQueryService service;

    UUID callerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ChatQueryService(
                authorizationService, matchRepository, messageRepository, notifSettingsRepo,
                cursorCodec, mapper, signingService, jdbc, activityStatusService);
    }

    @Test
    void getInbox_unreadFilter_onlyCountsMessagesFromOtherUser() {
        doNothing().when(authorizationService).requireActiveAccount(callerId);
        when(activityStatusService.now()).thenReturn(Instant.now());
        doNothing().when(jdbc).query(anyString(), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));

        service.getInbox(callerId, "UNREAD", null, 25);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sqlCaptor.capture(), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));
        String sql = sqlCaptor.getValue();

        assertThat(sql).contains("EXISTS");
        assertThat(sql).contains("msg.sender_user_id <> :userId");
        assertThat(sql).contains("msg.sequence_number >");
        assertThat(sql).contains("msg.deleted_at IS NULL");
        assertThat(sql).contains("msg.moderation_status = 'APPROVED'");
    }
}
