package com.qaliye.backend.appversion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppVersionServiceTest {

    private AppVersionService service;

    @BeforeEach
    void setUp() {
        AppVersionProperties props = new AppVersionProperties();

        AppVersionProperties.PlatformConfig android = new AppVersionProperties.PlatformConfig();
        android.setLatest("1.4.0");
        android.setMinimum("1.2.0");
        android.setForceUpdate(false);
        android.setStoreUrl("https://play.google.com/store/apps/details?id=com.qaliye.app");
        props.setAndroid(android);

        AppVersionProperties.PlatformConfig ios = new AppVersionProperties.PlatformConfig();
        ios.setLatest("1.4.0");
        ios.setMinimum("1.2.0");
        ios.setForceUpdate(true);
        ios.setStoreUrl("https://apps.apple.com/app/qaliye/id123456789");
        props.setIos(ios);

        service = new AppVersionService(props);
    }

    @Test
    void getVersion_android_returnsAndroidConfig() {
        AppVersionResponse response = service.getVersion("android");

        assertThat(response.platform()).isEqualTo("android");
        assertThat(response.latestVersion()).isEqualTo("1.4.0");
        assertThat(response.minimumVersion()).isEqualTo("1.2.0");
        assertThat(response.forceUpdate()).isFalse();
        assertThat(response.storeUrl()).isEqualTo("https://play.google.com/store/apps/details?id=com.qaliye.app");
    }

    @Test
    void getVersion_ios_returnsIosConfig() {
        AppVersionResponse response = service.getVersion("ios");

        assertThat(response.platform()).isEqualTo("ios");
        assertThat(response.latestVersion()).isEqualTo("1.4.0");
        assertThat(response.minimumVersion()).isEqualTo("1.2.0");
        assertThat(response.forceUpdate()).isTrue();
        assertThat(response.storeUrl()).isEqualTo("https://apps.apple.com/app/qaliye/id123456789");
    }

    @Test
    void getVersion_androidUpperCase_parsedCorrectly() {
        AppVersionResponse response = service.getVersion("ANDROID");
        assertThat(response.platform()).isEqualTo("android");
    }

    @Test
    void getVersion_iosMixedCase_parsedCorrectly() {
        AppVersionResponse response = service.getVersion("iOS");
        assertThat(response.platform()).isEqualTo("ios");
    }

    @Test
    void getVersion_unsupportedPlatform_returns400() {
        assertThatThrownBy(() -> service.getVersion("windows"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(400);
    }

    @Test
    void getVersion_nullPlatform_returns400() {
        assertThatThrownBy(() -> service.getVersion(null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(400);
    }

    @Test
    void getVersion_androidAndIosAreIndependent() {
        AppVersionProperties props = new AppVersionProperties();

        AppVersionProperties.PlatformConfig android = new AppVersionProperties.PlatformConfig();
        android.setLatest("2.0.0");
        android.setMinimum("1.5.0");
        android.setForceUpdate(false);
        android.setStoreUrl("https://play.google.com/");
        props.setAndroid(android);

        AppVersionProperties.PlatformConfig ios = new AppVersionProperties.PlatformConfig();
        ios.setLatest("3.0.0");
        ios.setMinimum("2.0.0");
        ios.setForceUpdate(true);
        ios.setStoreUrl("https://apps.apple.com/");
        props.setIos(ios);

        AppVersionService svc = new AppVersionService(props);

        AppVersionResponse androidResp = svc.getVersion("android");
        AppVersionResponse iosResp = svc.getVersion("ios");

        assertThat(androidResp.latestVersion()).isEqualTo("2.0.0");
        assertThat(androidResp.minimumVersion()).isEqualTo("1.5.0");
        assertThat(androidResp.forceUpdate()).isFalse();

        assertThat(iosResp.latestVersion()).isEqualTo("3.0.0");
        assertThat(iosResp.minimumVersion()).isEqualTo("2.0.0");
        assertThat(iosResp.forceUpdate()).isTrue();
    }

    @Test
    void getVersion_forceUpdateFalse_returnsFalse() {
        AppVersionResponse response = service.getVersion("android");
        assertThat(response.forceUpdate()).isFalse();
    }

    @Test
    void getVersion_forceUpdateTrue_returnsTrue() {
        AppVersionResponse response = service.getVersion("ios");
        assertThat(response.forceUpdate()).isTrue();
    }

    @Test
    void getVersion_propertyBindingReflected_latestVersionIsCorrect() {
        AppVersionProperties props = new AppVersionProperties();
        AppVersionProperties.PlatformConfig android = new AppVersionProperties.PlatformConfig();
        android.setLatest("9.9.9");
        android.setMinimum("1.0.0");
        props.setAndroid(android);
        props.setIos(new AppVersionProperties.PlatformConfig());

        AppVersionResponse response = new AppVersionService(props).getVersion("android");
        assertThat(response.latestVersion()).isEqualTo("9.9.9");
    }

    @Test
    void getVersion_blankPlatform_returns400() {
        assertThatThrownBy(() -> service.getVersion("   "))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(400);
    }
}
