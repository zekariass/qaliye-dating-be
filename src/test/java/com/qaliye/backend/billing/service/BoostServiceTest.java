package com.qaliye.backend.billing.service;

import com.qaliye.backend.billing.BillingProperties;
import com.qaliye.backend.billing.repository.ActionLimitRepository;
import com.qaliye.backend.billing.repository.CreditLotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
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
    @Mock ActionCostService actionCostService;
    @Mock CreditService creditService;
    @Mock ActionLimitRepository actionLimitRepo;
    BillingProperties billingProps;
    BoostService service;

    UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        billingProps = new BillingProperties();
        billingProps.setBoostDurationMinutes(30);
        service = new BoostService(creditLotRepo, billingProps, jdbc, actionCostService, creditService, actionLimitRepo);
        lenient().when(jdbc.queryForObject(anyString(), anyMap(), eq(String.class))).thenReturn("PUBLIC");
        lenient().when(actionCostService.evaluate(any(), any()))
                .thenReturn(new ActionCostService.ActionCostResult(
                        null, 0, true, false, false,
                        LocalDate.now(), LocalDate.now(), 0, null, "DAY"));
    }

    @Test
    void activateBoost_success() {
        Instant start = Instant.now();
        Instant end = start.plusSeconds(1800);

        when(creditLotRepo.findActiveBoost(userId)).thenReturn(Collections.emptyList());
        when(creditLotRepo.insertBoost(eq(userId), isNull(), eq(30)))
                .thenReturn(new CreditLotRepository.BoostInsertRow(UUID.randomUUID(), start, end));
        when(creditService.getBalance(userId)).thenReturn(2L);

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
        when(creditLotRepo.findActiveBoost(userId)).thenReturn(Collections.emptyList());
        when(actionCostService.evaluate(any(), any()))
                .thenReturn(new ActionCostService.ActionCostResult(
                        null, 1, true, false, false,
                        LocalDate.now(), LocalDate.now(), 0, null, "DAY"));
        when(creditService.consumeCredits(any(), anyLong(), any(), any()))
                .thenThrow(new CreditService.InsufficientCreditsException("not enough"));

        assertThatThrownBy(() -> service.activateBoost(userId, null))
                .isInstanceOf(CreditService.InsufficientCreditsException.class);
    }
}
