package com.qaliye.backend.activity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActivityStatusServiceTest {

    private ActivityStatusProperties props;
    private Clock clock;
    private ActivityStatusService service;

    private static final Instant FIXED_NOW = Instant.parse("2024-01-15T12:00:00Z");

    @BeforeEach
    void setUp() {
        props = new ActivityStatusProperties();
        props.setOnlineWindowSeconds(180);
        props.setRecentlyActiveWindowSeconds(900);
        props.setHeartbeatWriteMinIntervalSeconds(60);

        clock = mock(Clock.class);
        when(clock.instant()).thenReturn(FIXED_NOW);

        service = new ActivityStatusService(props, clock, mock(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.class));
    }

    @Test
    void resolve_showActivityFalse_returnsHidden() {
        OffsetDateTime lastActive = OffsetDateTime.ofInstant(FIXED_NOW.minusSeconds(10), ZoneOffset.UTC);
        assertThat(service.resolve(false, lastActive, FIXED_NOW)).isEqualTo(ActivityStatus.HIDDEN);
    }

    @Test
    void resolve_showActivityFalse_nullLastActive_returnsHidden() {
        assertThat(service.resolve(false, null, FIXED_NOW)).isEqualTo(ActivityStatus.HIDDEN);
    }

    @Test
    void resolve_nullLastActive_returnsOffline() {
        assertThat(service.resolve(true, null, FIXED_NOW)).isEqualTo(ActivityStatus.OFFLINE);
    }

    @Test
    void resolve_justActive_returnsOnline() {
        OffsetDateTime lastActive = OffsetDateTime.ofInstant(FIXED_NOW.minusSeconds(0), ZoneOffset.UTC);
        assertThat(service.resolve(true, lastActive, FIXED_NOW)).isEqualTo(ActivityStatus.ONLINE);
    }

    @Test
    void resolve_withinOnlineWindow_returnsOnline() {
        OffsetDateTime lastActive = OffsetDateTime.ofInstant(FIXED_NOW.minusSeconds(179), ZoneOffset.UTC);
        assertThat(service.resolve(true, lastActive, FIXED_NOW)).isEqualTo(ActivityStatus.ONLINE);
    }

    @Test
    void resolve_exactlyAtOnlineWindowBoundary_returnsOnline() {
        OffsetDateTime lastActive = OffsetDateTime.ofInstant(FIXED_NOW.minusSeconds(180), ZoneOffset.UTC);
        assertThat(service.resolve(true, lastActive, FIXED_NOW)).isEqualTo(ActivityStatus.ONLINE);
    }

    @Test
    void resolve_justOverOnlineWindow_returnsRecentlyActive() {
        OffsetDateTime lastActive = OffsetDateTime.ofInstant(FIXED_NOW.minusSeconds(181), ZoneOffset.UTC);
        assertThat(service.resolve(true, lastActive, FIXED_NOW)).isEqualTo(ActivityStatus.RECENTLY_ACTIVE);
    }

    @Test
    void resolve_withinRecentlyActiveWindow_returnsRecentlyActive() {
        OffsetDateTime lastActive = OffsetDateTime.ofInstant(FIXED_NOW.minusSeconds(500), ZoneOffset.UTC);
        assertThat(service.resolve(true, lastActive, FIXED_NOW)).isEqualTo(ActivityStatus.RECENTLY_ACTIVE);
    }

    @Test
    void resolve_exactlyAtRecentlyActiveWindowBoundary_returnsRecentlyActive() {
        OffsetDateTime lastActive = OffsetDateTime.ofInstant(FIXED_NOW.minusSeconds(900), ZoneOffset.UTC);
        assertThat(service.resolve(true, lastActive, FIXED_NOW)).isEqualTo(ActivityStatus.RECENTLY_ACTIVE);
    }

    @Test
    void resolve_justOverRecentlyActiveWindow_returnsOffline() {
        OffsetDateTime lastActive = OffsetDateTime.ofInstant(FIXED_NOW.minusSeconds(901), ZoneOffset.UTC);
        assertThat(service.resolve(true, lastActive, FIXED_NOW)).isEqualTo(ActivityStatus.OFFLINE);
    }

    @Test
    void resolve_longAgo_returnsOffline() {
        OffsetDateTime lastActive = OffsetDateTime.ofInstant(FIXED_NOW.minusSeconds(3600), ZoneOffset.UTC);
        assertThat(service.resolve(true, lastActive, FIXED_NOW)).isEqualTo(ActivityStatus.OFFLINE);
    }

    @Test
    void resolve_futureTimestamp_treatsAsOnline() {
        OffsetDateTime lastActive = OffsetDateTime.ofInstant(FIXED_NOW.plusSeconds(10), ZoneOffset.UTC);
        assertThat(service.resolve(true, lastActive, FIXED_NOW)).isEqualTo(ActivityStatus.ONLINE);
    }

    @Test
    void now_delegatesToClock() {
        assertThat(service.now()).isEqualTo(FIXED_NOW);
    }

    @ParameterizedTest
    @CsvSource({
        "0,   ONLINE",
        "180, ONLINE",
        "181, RECENTLY_ACTIVE",
        "899, RECENTLY_ACTIVE",
        "900, RECENTLY_ACTIVE",
        "901, OFFLINE"
    })
    void resolve_boundaries(long secondsAgo, ActivityStatus expected) {
        OffsetDateTime lastActive = OffsetDateTime.ofInstant(FIXED_NOW.minusSeconds(secondsAgo), ZoneOffset.UTC);
        assertThat(service.resolve(true, lastActive, FIXED_NOW)).isEqualTo(expected);
    }
}
