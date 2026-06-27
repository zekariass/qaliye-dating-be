package com.qaliye.backend.notifications;

import com.qaliye.backend.notifications.repository.NotificationOutboxRepository.OutboxRow;
import com.qaliye.backend.notifications.service.NotificationEligibilityService;
import com.qaliye.backend.notifications.service.NotificationEligibilityService.EligibilityResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationEligibilityServiceTest {

    @Mock NamedParameterJdbcTemplate jdbc;

    NotificationEligibilityService service;

    UUID recipientId = UUID.randomUUID();
    UUID matchId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    UUID actionId = UUID.randomUUID();
    UUID campaignId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new NotificationEligibilityService(jdbc);
    }

    @Test
    void checkOutboxEligibility_expiredEvent_returnsSkip() {
        OutboxRow row = buildRow("CHAT_MESSAGE", OffsetDateTime.now().minusMinutes(5));

        EligibilityResult result = service.checkOutboxEligibility(row);

        assertThat(result.eligible()).isFalse();
        assertThat(result.skipReason()).isEqualTo("NOTIFICATION_EXPIRED");
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void checkOutboxEligibility_inactiveRecipient_returnsSkip() {
        OutboxRow row = buildRow("CHAT_MESSAGE", null);
        when(jdbc.queryForObject(contains("app_users"), anyMap(), eq(Integer.class)))
                .thenReturn(0);

        EligibilityResult result = service.checkOutboxEligibility(row);

        assertThat(result.eligible()).isFalse();
        assertThat(result.skipReason()).isEqualTo("RECIPIENT_INELIGIBLE");
    }

    @Test
    void checkOutboxEligibility_activeUser_chatMessage_eligible_returnsOk() {
        OutboxRow row = buildRow("CHAT_MESSAGE", null);
        when(jdbc.queryForObject(contains("app_users"), anyMap(), eq(Integer.class)))
                .thenReturn(1);
        when(jdbc.queryForObject(contains("matches"), anyMap(), eq(Integer.class)))
                .thenReturn(1);

        EligibilityResult result = service.checkOutboxEligibility(row);

        assertThat(result.eligible()).isTrue();
    }

    @Test
    void checkOutboxEligibility_chatMessage_matchBlocked_returnsSkip() {
        OutboxRow row = buildRow("CHAT_MESSAGE", null);
        when(jdbc.queryForObject(contains("app_users"), anyMap(), eq(Integer.class)))
                .thenReturn(1);
        when(jdbc.queryForObject(contains("matches"), anyMap(), eq(Integer.class)))
                .thenReturn(0);

        EligibilityResult result = service.checkOutboxEligibility(row);

        assertThat(result.eligible()).isFalse();
        assertThat(result.skipReason()).isEqualTo("CHAT_NOT_ELIGIBLE");
    }

    @Test
    void checkOutboxEligibility_likeReceived_prefDisabled_returnsSkip() {
        OutboxRow row = buildLikeRow();
        when(jdbc.queryForObject(contains("app_users"), anyMap(), eq(Integer.class)))
                .thenReturn(1);
        when(jdbc.queryForObject(contains("user_notification_preferences"), anyMap(), eq(Integer.class)))
                .thenReturn(0);

        EligibilityResult result = service.checkOutboxEligibility(row);

        assertThat(result.eligible()).isFalse();
        assertThat(result.skipReason()).isEqualTo("LIKE_PREF_DISABLED");
    }

    @Test
    void checkOutboxEligibility_marketing_notOptedIn_returnsSkip() {
        OutboxRow row = buildMarketingRow();
        when(jdbc.queryForObject(contains("app_users"), anyMap(), eq(Integer.class)))
                .thenReturn(1);
        when(jdbc.queryForObject(contains("user_notification_preferences"), anyMap(), eq(Integer.class)))
                .thenReturn(0);

        EligibilityResult result = service.checkOutboxEligibility(row);

        assertThat(result.eligible()).isFalse();
        assertThat(result.skipReason()).isEqualTo("MARKETING_NOT_ELIGIBLE");
    }

    @Test
    void checkOutboxEligibility_accountAlert_activeUser_alwaysEligible() {
        OutboxRow row = buildAccountAlertRow();
        when(jdbc.queryForObject(contains("app_users"), anyMap(), eq(Integer.class)))
                .thenReturn(1);

        EligibilityResult result = service.checkOutboxEligibility(row);

        assertThat(result.eligible()).isTrue();
    }

    @Test
    void checkOutboxEligibility_unknownType_returnsSkip() {
        OutboxRow row = buildRowWithType("UNKNOWN_TYPE");
        when(jdbc.queryForObject(contains("app_users"), anyMap(), eq(Integer.class)))
                .thenReturn(1);

        EligibilityResult result = service.checkOutboxEligibility(row);

        assertThat(result.eligible()).isFalse();
        assertThat(result.skipReason()).isEqualTo("UNKNOWN_TYPE");
    }

    @Test
    void checkDeliveryEligibility_deviceReassigned_returnsSkip() {
        OutboxRow row = buildRow("CHAT_MESSAGE", null);

        EligibilityResult result = service.checkDeliveryEligibility(
                UUID.randomUUID(), recipientId, true, "PRODUCTION", "PRODUCTION", row);

        assertThat(result.eligible()).isFalse();
        assertThat(result.skipReason()).isEqualTo("DEVICE_REASSIGNED");
    }

    @Test
    void checkDeliveryEligibility_deviceInactive_returnsSkip() {
        OutboxRow row = buildRow("CHAT_MESSAGE", null);

        EligibilityResult result = service.checkDeliveryEligibility(
                recipientId, recipientId, false, "PRODUCTION", "PRODUCTION", row);

        assertThat(result.eligible()).isFalse();
        assertThat(result.skipReason()).isEqualTo("DEVICE_INACTIVE");
    }

    @Test
    void checkDeliveryEligibility_environmentMismatch_returnsSkip() {
        OutboxRow row = buildRow("CHAT_MESSAGE", null);

        EligibilityResult result = service.checkDeliveryEligibility(
                recipientId, recipientId, true, "DEVELOPMENT", "PRODUCTION", row);

        assertThat(result.eligible()).isFalse();
        assertThat(result.skipReason()).isEqualTo("ENVIRONMENT_MISMATCH");
    }

    private OutboxRow buildRow(String type, OffsetDateTime expiresAt) {
        return new OutboxRow(UUID.randomUUID(), type, recipientId, null,
                matchId, messageId, null, null,
                "dedupe", "collapse", "{}", "PENDING", 1,
                OffsetDateTime.now(), expiresAt, OffsetDateTime.now());
    }

    private OutboxRow buildRowWithType(String type) {
        return buildRow(type, null);
    }

    private OutboxRow buildLikeRow() {
        return new OutboxRow(UUID.randomUUID(), "LIKE_RECEIVED", recipientId, null,
                null, null, actionId, null,
                "dedupe", null, "{}", "PENDING", 1,
                OffsetDateTime.now(), null, OffsetDateTime.now());
    }

    private OutboxRow buildMarketingRow() {
        return new OutboxRow(UUID.randomUUID(), "MARKETING", recipientId, null,
                null, null, null, campaignId,
                "dedupe", "collapse", "{}", "PENDING", 1,
                OffsetDateTime.now(), null, OffsetDateTime.now());
    }

    private OutboxRow buildAccountAlertRow() {
        return new OutboxRow(UUID.randomUUID(), "ACCOUNT_ALERT", recipientId, null,
                null, null, null, null,
                "dedupe", null, "{}", "PENDING", 1,
                OffsetDateTime.now(), null, OffsetDateTime.now());
    }
}
