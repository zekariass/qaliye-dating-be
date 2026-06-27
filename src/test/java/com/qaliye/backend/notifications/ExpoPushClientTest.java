package com.qaliye.backend.notifications;

import com.qaliye.backend.notifications.config.PushProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpoPushClientTest {

    @Mock RestClient.Builder restClientBuilder;
    @Mock RestClient restClient;

    PushProperties props = new PushProperties();

    @BeforeEach
    void setUp() {
        when(restClientBuilder.build()).thenReturn(restClient);
    }

    @Test
    void sendBatch_emptyMessages_returnsEmptyList() {
        ExpoPushClient client = new ExpoPushClient(restClientBuilder, props);
        assertThat(client.sendBatch(List.of())).isEmpty();
    }

    @Test
    void fetchReceipts_emptyIds_returnsEmptyMap() {
        ExpoPushClient client = new ExpoPushClient(restClientBuilder, props);
        assertThat(client.fetchReceipts(List.of())).isEmpty();
    }

    @Test
    void ticketResult_isOk_returnsTrue_forOkStatus() {
        ExpoPushClient.TicketResult result = new ExpoPushClient.TicketResult("ok", "abc-123", null, null);
        assertThat(result.isOk()).isTrue();
        assertThat(result.isDeviceNotRegistered()).isFalse();
        assertThat(result.isRetryable()).isFalse();
    }

    @Test
    void ticketResult_deviceNotRegistered_returnsCorrectFlags() {
        ExpoPushClient.TicketResult result = new ExpoPushClient.TicketResult(
                "error", null, "DeviceNotRegistered", "Device not registered");
        assertThat(result.isOk()).isFalse();
        assertThat(result.isDeviceNotRegistered()).isTrue();
        assertThat(result.isRetryable()).isFalse();
    }

    @Test
    void ticketResult_messageRateExceeded_isRetryable() {
        ExpoPushClient.TicketResult result = new ExpoPushClient.TicketResult(
                "error", null, "MessageRateExceeded", "Too many messages");
        assertThat(result.isRetryable()).isTrue();
    }

    @Test
    void receiptResult_isOk_returnsTrue_forOkStatus() {
        ExpoPushClient.ReceiptResult result = new ExpoPushClient.ReceiptResult("ok", null, null);
        assertThat(result.isOk()).isTrue();
        assertThat(result.isDeviceNotRegistered()).isFalse();
    }

    @Test
    void receiptResult_deviceNotRegistered_returnsCorrectFlags() {
        ExpoPushClient.ReceiptResult result = new ExpoPushClient.ReceiptResult(
                "error", "DeviceNotRegistered", "Device not registered");
        assertThat(result.isOk()).isFalse();
        assertThat(result.isDeviceNotRegistered()).isTrue();
    }
}
