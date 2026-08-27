package com.qaliye.backend.appversion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppVersionPropertiesTest {

    @Test
    void validate_defaultValues_noException() {
        AppVersionProperties props = buildProps("1.0.0", "1.0.0", "1.0.0", "1.0.0");
        assertThatCode(props::validate).doesNotThrowAnyException();
    }

    @Test
    void validate_validSemanticVersions_noException() {
        AppVersionProperties props = buildProps("1.4.0", "1.2.0", "1.10.3", "1.0.0");
        assertThatCode(props::validate).doesNotThrowAnyException();
    }

    @Test
    void validate_androidLatestMalformed_throwsIllegalState() {
        AppVersionProperties props = buildProps("1.4", "1.0.0", "1.0.0", "1.0.0");
        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("android")
                .hasMessageContaining("latest");
    }

    @Test
    void validate_androidMinimumMalformed_throwsIllegalState() {
        AppVersionProperties props = buildProps("1.0.0", "bad-version", "1.0.0", "1.0.0");
        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("android")
                .hasMessageContaining("minimum");
    }

    @Test
    void validate_iosLatestMalformed_throwsIllegalState() {
        AppVersionProperties props = buildProps("1.0.0", "1.0.0", "1.4", "1.0.0");
        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ios")
                .hasMessageContaining("latest");
    }

    @Test
    void validate_iosMinimumMalformed_throwsIllegalState() {
        AppVersionProperties props = buildProps("1.0.0", "1.0.0", "1.0.0", "1.0.0.0");
        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ios")
                .hasMessageContaining("minimum");
    }

    @Test
    void validate_androidLatestNull_throwsIllegalState() {
        AppVersionProperties props = buildProps(null, "1.0.0", "1.0.0", "1.0.0");
        assertThatThrownBy(props::validate).isInstanceOf(IllegalStateException.class);
    }

    private AppVersionProperties buildProps(String androidLatest, String androidMin,
                                            String iosLatest, String iosMin) {
        AppVersionProperties props = new AppVersionProperties();

        AppVersionProperties.PlatformConfig android = new AppVersionProperties.PlatformConfig();
        android.setLatest(androidLatest);
        android.setMinimum(androidMin);
        props.setAndroid(android);

        AppVersionProperties.PlatformConfig ios = new AppVersionProperties.PlatformConfig();
        ios.setLatest(iosLatest);
        ios.setMinimum(iosMin);
        props.setIos(ios);

        return props;
    }
}
