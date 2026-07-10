package com.qaliye.backend.auth;

import com.qaliye.backend.auth.sms.AfroMessageSmsProvider;
import com.qaliye.backend.auth.sms.PhoneNormalizer;
import com.qaliye.backend.auth.sms.SmsConfig;
import com.qaliye.backend.auth.sms.SmsEthiopiaSmsProvider;
import com.qaliye.backend.auth.sms.SmsProperties;
import com.qaliye.backend.auth.sms.SmsProvider;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmsConfigTest {

    private final SmsConfig config = new SmsConfig();

    @Test
    void afromessageProvider_isSelected() {
        SmsProperties props = new SmsProperties();
        props.setProvider("AFROMESSAGE");

        SmsProvider provider = config.smsProvider(props, RestClient.builder());

        assertThat(provider).isInstanceOf(AfroMessageSmsProvider.class);
    }

    @Test
    void smsEthiopiaProvider_isSelected() {
        SmsProperties props = new SmsProperties();
        props.setProvider("SMS_ETHIOPIA");

        SmsProvider provider = config.smsProvider(props, RestClient.builder());

        assertThat(provider).isInstanceOf(SmsEthiopiaSmsProvider.class);
    }

    @Test
    void providerNameIsCaseInsensitive() {
        SmsProperties props = new SmsProperties();
        props.setProvider("afromessage");

        SmsProvider provider = config.smsProvider(props, RestClient.builder());

        assertThat(provider).isInstanceOf(AfroMessageSmsProvider.class);
    }

    @Test
    void unknownProvider_throwsIllegalState() {
        SmsProperties props = new SmsProperties();
        props.setProvider("TWILIO");

        assertThatThrownBy(() -> config.smsProvider(props, RestClient.builder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TWILIO");
    }

    @Test
    void blankProvider_throwsIllegalState() {
        SmsProperties props = new SmsProperties();
        props.setProvider("");

        assertThatThrownBy(() -> config.smsProvider(props, RestClient.builder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sms.provider must be configured");
    }

    @Test
    void nullProvider_throwsIllegalState() {
        SmsProperties props = new SmsProperties();
        props.setProvider(null);

        assertThatThrownBy(() -> config.smsProvider(props, RestClient.builder()))
                .isInstanceOf(IllegalStateException.class);
    }
}

class PhoneNormalizerTest {

    @Test
    void alreadyE164_returnsSame() {
        assertThat(PhoneNormalizer.normalizeEthiopian("+251911234567"))
                .isEqualTo("+251911234567");
    }

    @Test
    void plusPrefixMissing_addsPlus() {
        assertThat(PhoneNormalizer.normalizeEthiopian("251911234567"))
                .isEqualTo("+251911234567");
    }

    @Test
    void localLeadingZero_replacesWithCountryCode() {
        assertThat(PhoneNormalizer.normalizeEthiopian("0911234567"))
                .isEqualTo("+251911234567");
    }

    @Test
    void nineDigitMobile_prependsCountryCode() {
        assertThat(PhoneNormalizer.normalizeEthiopian("911234567"))
                .isEqualTo("+251911234567");
    }

    @Test
    void withSpacesAndDashes_normalizes() {
        assertThat(PhoneNormalizer.normalizeEthiopian("+251 911 234 567"))
                .isEqualTo("+251911234567");
        assertThat(PhoneNormalizer.normalizeEthiopian("09-11 234 567"))
                .isEqualTo("+251911234567");
    }

    @Test
    void unsupportedCountry_throwsBadRequest() {
        assertThatThrownBy(() -> PhoneNormalizer.normalizeEthiopian("+447911123456"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex ->
                        assertThat(((ResponseStatusException) ex).getStatusCode())
                                .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void tooShort_throwsBadRequest() {
        assertThatThrownBy(() -> PhoneNormalizer.normalizeEthiopian("+251911"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex ->
                        assertThat(((ResponseStatusException) ex).getStatusCode())
                                .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void nullOrBlank_throwsBadRequest() {
        assertThatThrownBy(() -> PhoneNormalizer.normalizeEthiopian(null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> PhoneNormalizer.normalizeEthiopian("  "))
                .isInstanceOf(ResponseStatusException.class);
    }
}
