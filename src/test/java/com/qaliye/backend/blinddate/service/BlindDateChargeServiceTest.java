package com.qaliye.backend.blinddate.service;

import com.qaliye.backend.billing.repository.ActionLimitRepository;
import com.qaliye.backend.billing.service.ActionCostService;
import com.qaliye.backend.billing.service.CreditService;
import com.qaliye.backend.discovery.exception.ActionLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BlindDateChargeServiceTest {

    @Mock ActionCostService actionCostService;
    @Mock ActionLimitRepository actionLimitRepo;
    @Mock CreditService creditService;

    BlindDateChargeService service;
    UUID userId = UUID.randomUUID();
    UUID ruleId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new BlindDateChargeService(actionCostService, actionLimitRepo, creditService);
    }

    private ActionCostService.ActionCostResult cost(long creditCost, Integer limitValue,
                                                    boolean blocked) {
        return new ActionCostService.ActionCostResult(
                ruleId, creditCost, true, false, blocked,
                LocalDate.now(), LocalDate.now().plusDays(1), 0, limitValue, "DAY");
    }

    @Test
    void charge_freeActionUnderLimit_consumesAllowanceNoCredits() {
        when(actionCostService.evaluate(userId, "BLIND_DATE_SESSION_CREATE"))
                .thenReturn(cost(0, 5, false));
        when(actionLimitRepo.tryIncrementUnderLimit(eq(userId), eq(ruleId), any(), eq(5)))
                .thenReturn(Optional.of(1));

        long charged = service.charge(userId, "BLIND_DATE_SESSION_CREATE", "key-1");

        assertThat(charged).isZero();
        verify(actionLimitRepo).ensureExists(eq(userId), eq(ruleId), any(), any());
        verify(creditService, never()).consumeCredits(any(), anyLong(), any(), any());
    }

    @Test
    void charge_limitReachedAndNoCredits_throwsLimitExceeded() {
        when(actionCostService.evaluate(userId, "BLIND_DATE_PARTICIPATE"))
                .thenReturn(cost(0, 5, false));
        when(actionLimitRepo.tryIncrementUnderLimit(eq(userId), eq(ruleId), any(), eq(5)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.charge(userId, "BLIND_DATE_PARTICIPATE", "key-2"))
                .isInstanceOf(ActionLimitExceededException.class);
        verify(creditService, never()).consumeCredits(any(), anyLong(), any(), any());
    }

    @Test
    void charge_limitReachedButCreditsRequired_consumesCredits() {
        when(actionCostService.evaluate(userId, "BLIND_DATE_SESSION_CREATE"))
                .thenReturn(cost(10, 5, false));
        when(actionLimitRepo.tryIncrementUnderLimit(eq(userId), eq(ruleId), any(), eq(5)))
                .thenReturn(Optional.empty());

        long charged = service.charge(userId, "BLIND_DATE_SESSION_CREATE", "key-3");

        assertThat(charged).isEqualTo(10);
        verify(creditService).consumeCredits(userId, 10L, "BLIND_DATE_SESSION_CREATE", "key-3");
    }

    @Test
    void charge_blockedAction_throwsLimitExceeded() {
        when(actionCostService.evaluate(userId, "BLIND_DATE_PARTICIPATE"))
                .thenReturn(cost(0, null, true));

        assertThatThrownBy(() -> service.charge(userId, "BLIND_DATE_PARTICIPATE", "key-4"))
                .isInstanceOf(ActionLimitExceededException.class);
    }

    @Test
    void charge_noRuleNoLimit_creditsRequired_consumesCredits() {
        when(actionCostService.evaluate(userId, "BLIND_DATE_SESSION_CREATE"))
                .thenReturn(cost(25, null, false));

        long charged = service.charge(userId, "BLIND_DATE_SESSION_CREATE", "key-5");

        assertThat(charged).isEqualTo(25);
        verify(creditService).consumeCredits(userId, 25L, "BLIND_DATE_SESSION_CREATE", "key-5");
        verifyNoInteractions(actionLimitRepo);
    }
}
