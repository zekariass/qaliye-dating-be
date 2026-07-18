package com.qaliye.backend.billing.service;

import com.qaliye.backend.billing.BillingProperties;
import com.qaliye.backend.billing.repository.CreditLotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BoostServiceTest {

    @Mock CreditLotRepository creditLotRepo;
    @Mock NamedParameterJdbcTemplate jdbc;
    BillingProperties billingProps;
    BoostService service;

    UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        billingProps = new BillingProperties();
        billingProps.setBoostDurationMinutes(30);
        service = new BoostService(creditLotRepo, billingProps, jdbc);
        lenient().when(jdbc.queryForObject(anyString(), anyMap(), eq(String.class))).thenReturn("PUBLIC");
    }

    @Test
    void activateBoost_success() {
        UUID lotId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        Instant start = Instant.now();
        Instant end = start.plusSeconds(1800);

        when(creditLotRepo.findActiveBoost(userId)).thenReturn(Collections.emptyList());
        when(creditLotRepo.insertLedgerEntry(eq(userId), eq("BOOST_CREDIT"), eq(-1),
                eq("CONSUMPTION"), any(), any(), any(), anyString(), any(), any()))
                .thenReturn(ledgerId);
        when(creditLotRepo.findOldestValidLot(userId, "BOOST_CREDIT"))
                .thenReturn(List.of(new CreditLotRepository.LotRow(lotId, 3)));
        when(creditLotRepo.decrementLot(lotId, 1)).thenReturn(1);
        when(creditLotRepo.insertBoost(eq(userId), eq(ledgerId), eq(30)))
                .thenReturn(new CreditLotRepository.BoostInsertRow(UUID.randomUUID(), start, end));
        when(creditLotRepo.getBalance(userId, "BOOST_CREDIT")).thenReturn(2);

        var response = service.activateBoost(userId, null);

        assertThat(response.startedAt()).isEqualTo(start);
        assertThat(response.expiresAt()).isEqualTo(end);
        assertThat(response.creditsRemaining()).isEqualTo(2);
    }

    @Test
    void activateBoost_alreadyActive_throwsConflict() {
        when(creditLotRepo.findActiveBoost(userId))
                .thenReturn(List.of(new CreditLotRepository.ActiveBoostRow(
                        UUID.randomUUID(), Instant.now(), Instant.now().plusSeconds(1800))));

        assertThatThrownBy(() -> service.activateBoost(userId, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("boost_already_active");
    }

    @Test
    void activateBoost_incognitoMode_throwsConflict() {
        when(jdbc.queryForObject(anyString(), anyMap(), eq(String.class))).thenReturn("INCOGNITO");

        assertThatThrownBy(() -> service.activateBoost(userId, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("cannot_boost_while_incognito");
    }

    @Test
    void activateBoost_insufficientCredits_throwsPaymentRequired() {
        UUID ledgerId = UUID.randomUUID();
        when(creditLotRepo.findActiveBoost(userId)).thenReturn(Collections.emptyList());
        when(creditLotRepo.insertLedgerEntry(eq(userId), eq("BOOST_CREDIT"), eq(-1),
                eq("CONSUMPTION"), any(), any(), any(), anyString(), any(), any()))
                .thenReturn(ledgerId);
        when(creditLotRepo.findOldestValidLot(userId, "BOOST_CREDIT"))
                .thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> service.activateBoost(userId, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("insufficient_boost_credits");
    }
}
