package com.qaliye.backend.auth;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaliye.backend.auth.hook.SendSmsHookService;
import com.qaliye.backend.auth.hook.SupabaseHookVerifier;
import com.qaliye.backend.auth.sms.SmsDeliveryException;
import com.qaliye.backend.auth.sms.SmsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SendSmsHookServiceTest {

    private static final byte[] TEST_KEY =
            "test-key-32-bytes-for-hmac-12345".getBytes(StandardCharsets.UTF_8);
    private static final String TEST_SECRET =
            "v1,whsec_" + Base64.getEncoder().encodeToString(TEST_KEY);

    @Mock
    SmsProvider smsProvider;

    SendSmsHookService service;

    @BeforeEach
    void setUp() {
        service = new SendSmsHookService(new SupabaseHookVerifier(), smsProvider, new ObjectMapper());
        ReflectionTestUtils.setField(service, "hookSecret", TEST_SECRET);
    }

    private static String freshTimestamp() {
        return String.valueOf(Instant.now().getEpochSecond());
    }

    private static String sign(String webhookId, String timestamp, String body) throws Exception {
        String content = webhookId + "." + timestamp + "." + body;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(TEST_KEY, "HmacSHA256"));
        byte[] hmac = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        return "v1," + Base64.getEncoder().encodeToString(hmac);
    }

    @Test
    void validPayload_sendsSmsViaConfiguredProvider() throws Exception {
        String ts = freshTimestamp();
        String body = "{\"user\":{\"phone\":\"+251911234567\"},\"sms\":{\"otp\":\"123456\"}}";
        String sig = sign("msg-001", ts, body);

        assertThatNoException().isThrownBy(() ->
                service.handle(body.getBytes(StandardCharsets.UTF_8), "msg-001", ts, sig));

        ArgumentCaptor<String> toCaptor = ArgumentCaptor.forClass(String.class);
        verify(smsProvider).send(toCaptor.capture(), anyString());
        assertThat(toCaptor.getValue()).isEqualTo("+251911234567");
    }

    @Test
    void onlyOneProvider_calledPerRequest() throws Exception {
        String ts = freshTimestamp();
        String body = "{\"user\":{\"phone\":\"+251911234567\"},\"sms\":{\"otp\":\"123456\"}}";
        String sig = sign("msg-002", ts, body);

        service.handle(body.getBytes(StandardCharsets.UTF_8), "msg-002", ts, sig);

        verify(smsProvider, times(1)).send(any(), any());
    }

    @Test
    void missingPhone_rejects() throws Exception {
        String ts = freshTimestamp();
        String body = "{\"user\":{},\"sms\":{\"otp\":\"123456\"}}";
        String sig = sign("msg-003", ts, body);

        assertThatThrownBy(() ->
                service.handle(body.getBytes(StandardCharsets.UTF_8), "msg-003", ts, sig))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex ->
                        assertThat(((ResponseStatusException) ex).getStatusCode())
                                .isEqualTo(HttpStatus.BAD_REQUEST));

        verifyNoInteractions(smsProvider);
    }

    @Test
    void missingOtp_rejects() throws Exception {
        String ts = freshTimestamp();
        String body = "{\"user\":{\"phone\":\"+251911234567\"},\"sms\":{}}";
        String sig = sign("msg-004", ts, body);

        assertThatThrownBy(() ->
                service.handle(body.getBytes(StandardCharsets.UTF_8), "msg-004", ts, sig))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex ->
                        assertThat(((ResponseStatusException) ex).getStatusCode())
                                .isEqualTo(HttpStatus.BAD_REQUEST));

        verifyNoInteractions(smsProvider);
    }

    @Test
    void phoneWithoutPlusPrefix_isNormalizedToE164() throws Exception {
        String ts = freshTimestamp();
        String body = "{\"user\":{\"phone\":\"251911234567\"},\"sms\":{\"otp\":\"123456\"}}";
        String sig = sign("msg-005", ts, body);

        service.handle(body.getBytes(StandardCharsets.UTF_8), "msg-005", ts, sig);

        verify(smsProvider).send(eq("+251911234567"), anyString());
    }

    @Test
    void phoneWithLocalPrefix_isNormalizedToE164() throws Exception {
        String ts = freshTimestamp();
        String body = "{\"user\":{\"phone\":\"0911234567\"},\"sms\":{\"otp\":\"123456\"}}";
        String sig = sign("msg-005b", ts, body);

        service.handle(body.getBytes(StandardCharsets.UTF_8), "msg-005b", ts, sig);

        verify(smsProvider).send(eq("+251911234567"), anyString());
    }

    @Test
    void unsupportedCountryCode_rejects() throws Exception {
        String ts = freshTimestamp();
        String body = "{\"user\":{\"phone\":\"+447911123456\"},\"sms\":{\"otp\":\"123456\"}}";
        String sig = sign("msg-005c", ts, body);

        assertThatThrownBy(() ->
                service.handle(body.getBytes(StandardCharsets.UTF_8), "msg-005c", ts, sig))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex ->
                        assertThat(((ResponseStatusException) ex).getStatusCode())
                                .isEqualTo(HttpStatus.BAD_REQUEST));

        verifyNoInteractions(smsProvider);
    }

    @Test
    void invalidSignature_rejects() {
        String ts = freshTimestamp();
        String body = "{\"user\":{\"phone\":\"+251911234567\"},\"sms\":{\"otp\":\"123456\"}}";

        assertThatThrownBy(() ->
                service.handle(body.getBytes(StandardCharsets.UTF_8), "msg-006", ts, "v1,badsignaturevalue=="))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex ->
                        assertThat(((ResponseStatusException) ex).getStatusCode())
                                .isEqualTo(HttpStatus.UNAUTHORIZED));

        verifyNoInteractions(smsProvider);
    }

    @Test
    void providerFailure_returnsBadGateway() throws Exception {
        String ts = freshTimestamp();
        String body = "{\"user\":{\"phone\":\"+251911234567\"},\"sms\":{\"otp\":\"123456\"}}";
        String sig = sign("msg-007", ts, body);

        doThrow(new SmsDeliveryException("connection timeout"))
                .when(smsProvider).send(any(), any());

        assertThatThrownBy(() ->
                service.handle(body.getBytes(StandardCharsets.UTF_8), "msg-007", ts, sig))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex ->
                        assertThat(((ResponseStatusException) ex).getStatusCode())
                                .isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void providerSuccess_returns200() throws Exception {
        String ts = freshTimestamp();
        String body = "{\"user\":{\"phone\":\"+251911234567\"},\"sms\":{\"otp\":\"123456\"}}";
        String sig = sign("msg-008", ts, body);

        assertThatNoException().isThrownBy(() ->
                service.handle(body.getBytes(StandardCharsets.UTF_8), "msg-008", ts, sig));

        verify(smsProvider).send(eq("+251911234567"), anyString());
    }

    @Test
    void otp_isNotLogged() throws Exception {
        String otp = "999777";
        String ts = freshTimestamp();
        String body = "{\"user\":{\"phone\":\"+251911234567\"},\"sms\":{\"otp\":\"" + otp + "\"}}";
        String sig = sign("msg-009", ts, body);

        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.setContext(loggerContext);
        listAppender.start();
        ch.qos.logback.classic.Logger rootLogger =
                loggerContext.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        rootLogger.addAppender(listAppender);

        try {
            service.handle(body.getBytes(StandardCharsets.UTF_8), "msg-009", ts, sig);
        } finally {
            rootLogger.detachAppender(listAppender);
            listAppender.stop();
        }

        assertThat(listAppender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(msg -> msg.contains(otp));
    }
}
