package com.qaliye.backend.activity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class ActivityStatusPropertiesTest {

    @Test
    void validate_defaultValues_noException() {
        ActivityStatusProperties props = new ActivityStatusProperties();
        props.setOnlineWindowSeconds(180);
        props.setRecentlyActiveWindowSeconds(900);
        props.setHeartbeatWriteMinIntervalSeconds(60);
        assertThatCode(props::validate).doesNotThrowAnyException();
    }

    @Test
    void validate_recentlyActiveWindowNotGreaterThanOnlineWindow_throwsIllegalState() {
        ActivityStatusProperties props = new ActivityStatusProperties();
        props.setOnlineWindowSeconds(900);
        props.setRecentlyActiveWindowSeconds(500);
        props.setHeartbeatWriteMinIntervalSeconds(60);
        assertThatThrownBy(props::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void validate_recentlyActiveWindowExactlyEqualsOnlineWindow_throwsIllegalState() {
        ActivityStatusProperties props = new ActivityStatusProperties();
        props.setOnlineWindowSeconds(300);
        props.setRecentlyActiveWindowSeconds(300);
        props.setHeartbeatWriteMinIntervalSeconds(60);
        assertThatThrownBy(props::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void validate_heartbeatIntervalEqualToOnlineWindow_throwsIllegalState() {
        ActivityStatusProperties props = new ActivityStatusProperties();
        props.setOnlineWindowSeconds(180);
        props.setRecentlyActiveWindowSeconds(900);
        props.setHeartbeatWriteMinIntervalSeconds(180);
        assertThatThrownBy(props::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void validate_heartbeatIntervalGreaterThanOnlineWindow_throwsIllegalState() {
        ActivityStatusProperties props = new ActivityStatusProperties();
        props.setOnlineWindowSeconds(180);
        props.setRecentlyActiveWindowSeconds(900);
        props.setHeartbeatWriteMinIntervalSeconds(200);
        assertThatThrownBy(props::validate).isInstanceOf(IllegalStateException.class);
    }
}
