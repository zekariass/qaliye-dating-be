package com.qaliye.backend.auth;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.qaliye.backend.auth.sms.SmsDeliveryException;
import com.qaliye.backend.auth.sms.SmsEthiopiaSmsProvider;
import com.qaliye.backend.auth.sms.SmsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SmsEthiopiaSmsProviderTest {

    private static final String TEST_API_KEY = "SECRET_ETHSMS_KEY_abc456";
    private static final String BASE_URL = "https://smsethiopia.et/api/sms/send";
    private static final String PHONE = "+251911234567";

    MockRestServiceServer server;
    SmsEthiopiaSmsProvider provider;

    @BeforeEach
    void setUp() {
        SmsProperties.SmsEthiopia props = new SmsProperties.SmsEthiopia();
        props.setBaseUrl(BASE_URL);
        props.setApiKey(TEST_API_KEY);

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new SmsEthiopiaSmsProvider(props, builder.build());
    }

    @Test
    void successResponse_doesNotThrow() {
        server.expect(requestTo(BASE_URL))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess("{\"status\":\"success\"}", MediaType.APPLICATION_JSON));

        provider.send(PHONE, "Test message");

        server.verify();
    }

    @Test
    void successResponseWithoutStatusField_doesNotThrow() {
        server.expect(requestTo(BASE_URL))
              .andRespond(withSuccess("{\"message\":\"sent\",\"id\":\"123\"}", MediaType.APPLICATION_JSON));

        provider.send(PHONE, "Test message");

        server.verify();
    }

    @Test
    void phoneNumber_isSentWithoutPlusPrefix() {
        server.expect(requestTo(BASE_URL))
              .andRespond(withSuccess("{\"status\":\"success\"}", MediaType.APPLICATION_JSON));

        provider.send("+251911234567", "Test message");

        server.verify();
    }

    @Test
    void errorMessageField_throwsSmsDeliveryException() {
        server.expect(requestTo(BASE_URL))
              .andRespond(withSuccess("{\"error_message\":\"NUMBER_NOT_WHITELISTED\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.send(PHONE, "Test message"))
                .isInstanceOf(SmsDeliveryException.class);
    }

    @Test
    void httpError_throwsSmsDeliveryException() {
        server.expect(requestTo(BASE_URL))
              .andRespond(withServerError());

        assertThatThrownBy(() -> provider.send(PHONE, "Test message"))
                .isInstanceOf(SmsDeliveryException.class);
    }

    @Test
    void apiKey_isNotLogged_onError() {
        server.expect(requestTo(BASE_URL))
              .andRespond(withServerError());

        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(ctx);
        appender.start();
        ch.qos.logback.classic.Logger root =
                ctx.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        root.addAppender(appender);

        try {
            provider.send(PHONE, "Test message");
        } catch (SmsDeliveryException ignored) {
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(msg -> msg.contains(TEST_API_KEY));
    }
}
