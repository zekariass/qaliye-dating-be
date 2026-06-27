package com.qaliye.backend.discovery.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LikesServiceTest {

    @Mock NamedParameterJdbcTemplate jdbc;

    @InjectMocks LikesService service;

    UUID userId = UUID.randomUUID();

    @Test
    void getLikes_received_excludesMatchedPairs() {
        service.getLikes(userId, "RECEIVED", 0, 25);

        verify(jdbc).query(anyString(), any(), any());
    }

    @Test
    void getLikes_sent_excludesMatchedPairs() {
        service.getLikes(userId, "SENT", 0, 25);

        verify(jdbc).query(anyString(), any(), any());
    }
}
