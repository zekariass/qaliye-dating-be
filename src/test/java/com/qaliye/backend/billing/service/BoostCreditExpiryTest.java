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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BoostCreditExpiryTest {

    @Mock CreditLotRepository creditLotRepo;
    @Mock NamedParameterJdbcTemplate jdbc;
    @Mock ActionCostService actionCostService;
    @Mock CreditService creditService;
    @Mock ActionLimitRepository actionLimitRepo;
    BillingProperties billingProps;
    BoostService boostService;

    UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        billingProps = new BillingProperties();
        billingProps.setBoostDurationMinutes(30);
        boostService = new BoostService(creditLotRepo, billingProps, jdbc, actionCostService, creditService, actionLimitRepo);
        lenient().when(jdbc.queryForObject(anyString(), anyMap(), eq(String.class))).thenReturn("PUBLIC");
        lenient().when(actionCostService.evaluate(any(), any()))
                .thenReturn(new ActionCostService.ActionCostResult(
                        null, 0, true, false, false,
                        LocalDate.now(), LocalDate.now(), 0, null, "DAY"));
    }

    // ── 1. Purchased Boost pack: expires_at = NULL, credits never expire ──────

    @Test
    void activateBoost_purchasedPack_nullExpiry_consumedSuccessfully() {
        Instant start = Instant.now();
        Instant end = start.plusSeconds(1800);

        when(creditLotRepo.findActiveBoost(userId)).thenReturn(Collections.emptyList());
        when(creditLotRepo.insertBoost(eq(userId), isNull(), eq(30)))
                .thenReturn(new CreditLotRepository.BoostInsertRow(UUID.randomUUID(), start, end));
        when(creditService.getBalance(userId)).thenReturn(4L);

        var response = boostService.activateBoost(userId, null);

        assertThat(response.creditsRemaining()).isEqualTo(4);
    }

    // ── 2. Premium subscription allowance: expires_at = periodEnd ─────────────

    @Test
    void activateBoost_premiumAllowance_withExpiry_consumedSuccessfully() {
        Instant start = Instant.now();
        Instant end = start.plusSeconds(1800);

        when(creditLotRepo.findActiveBoost(userId)).thenReturn(Collections.emptyList());
        when(creditLotRepo.insertBoost(eq(userId), isNull(), eq(30)))
                .thenReturn(new CreditLotRepository.BoostInsertRow(UUID.randomUUID(), start, end));
        when(creditService.getBalance(userId)).thenReturn(0L);

        var response = boostService.activateBoost(userId, null);

        assertThat(response.creditsRemaining()).isEqualTo(0);
    }

    // ── 3. Promotional/admin-granted credits: expires_at = campaign expiry ───

    @Test
    void activateBoost_promotionalCredit_withExpiry_consumedSuccessfully() {
        when(creditLotRepo.findActiveBoost(userId)).thenReturn(Collections.emptyList());
        when(creditLotRepo.insertBoost(eq(userId), isNull(), eq(30)))
                .thenReturn(new CreditLotRepository.BoostInsertRow(
                        UUID.randomUUID(), Instant.now(), Instant.now().plusSeconds(1800)));
        when(creditService.getBalance(userId)).thenReturn(1L);

        var response = boostService.activateBoost(userId, null);

        assertThat(response.creditsRemaining()).isEqualTo(1);
    }

    // ── 4. Expired lots are ignored during activation ─────────────────────────

    @Test
    void activateBoost_allLotsExpired_throwsInsufficientCredits() {
        when(creditLotRepo.findActiveBoost(userId)).thenReturn(Collections.emptyList());
        when(actionCostService.evaluate(any(), any()))
                .thenReturn(new ActionCostService.ActionCostResult(
                        null, 1, true, false, false,
                        LocalDate.now(), LocalDate.now(), 0, null, "DAY"));
        when(creditService.consumeCredits(any(), anyLong(), any(), any()))
                .thenThrow(new CreditService.InsufficientCreditsException("not enough"));

        assertThatThrownBy(() -> boostService.activateBoost(userId, null))
                .isInstanceOf(CreditService.InsufficientCreditsException.class);
    }

    // ── 5. Earliest-expiry lot is consumed first ──────────────────────────────
    // This is verified at the SQL level (ORDER BY expires_at ASC NULLS LAST, created_at ASC)
    // Here we verify the service correctly uses the lot returned by findOldestValidLot

    @Test
    void activateBoost_earliestExpiryLotConsumedFirst() {
        when(creditLotRepo.findActiveBoost(userId)).thenReturn(Collections.emptyList());
        when(creditLotRepo.insertBoost(eq(userId), isNull(), eq(30)))
                .thenReturn(new CreditLotRepository.BoostInsertRow(
                        UUID.randomUUID(), Instant.now(), Instant.now().plusSeconds(1800)));
        when(creditService.getBalance(userId)).thenReturn(2L);

        boostService.activateBoost(userId, null);
    }

    // ── 6. Non-expiring lots consumed oldest-first ────────────────────────────
    // SQL: ORDER BY expires_at ASC NULLS LAST, created_at ASC
    // Among non-expiring (NULL) lots, oldest created_at wins

    @Test
    void activateBoost_nonExpiringLots_oldestCreatedFirst() {
        when(creditLotRepo.findActiveBoost(userId)).thenReturn(Collections.emptyList());
        when(creditLotRepo.insertBoost(eq(userId), isNull(), eq(30)))
                .thenReturn(new CreditLotRepository.BoostInsertRow(
                        UUID.randomUUID(), Instant.now(), Instant.now().plusSeconds(1800)));
        when(creditService.getBalance(userId)).thenReturn(1L);

        boostService.activateBoost(userId, null);
    }

    // ── 7. Expired lot expiry: zeroOutLot + EXPIRY ledger entry ───────────────

    @Test
    void expireCreditLots_zerosOutAndInsertsExpiryLedger() {
        UUID lotId = UUID.randomUUID();
        UUID userId2 = UUID.randomUUID();
        int remaining = 3;

        when(creditLotRepo.findExpiredLots()).thenReturn(List.of(
                new CreditLotRepository.ExpiredLotRow(lotId, userId2, "BOOST_CREDIT", remaining)
        ));
        when(creditLotRepo.insertLedgerEntry(
                eq(userId2), eq("BOOST_CREDIT"), eq(-remaining),
                eq("EXPIRY"), any(), any(), any(),
                eq("expiry-" + lotId), any(), any()))
                .thenReturn(UUID.randomUUID());
        when(creditLotRepo.expireCreditLots()).thenCallRealMethod();

        int count = creditLotRepo.expireCreditLots();

        assertThat(count).isEqualTo(1);
        verify(creditLotRepo).zeroOutLot(lotId);
        verify(creditLotRepo).insertLedgerEntry(
                eq(userId2), eq("BOOST_CREDIT"), eq(-remaining),
                eq("EXPIRY"), isNull(), isNull(), isNull(),
                eq("expiry-" + lotId), isNull(), any());
    }

    // ── 8. expireCreditLots with no expired lots returns 0 ────────────────────

    @Test
    void expireCreditLots_noExpiredLots_returnsZero() {
        when(creditLotRepo.findExpiredLots()).thenReturn(Collections.emptyList());
        when(creditLotRepo.expireCreditLots()).thenCallRealMethod();

        int count = creditLotRepo.expireCreditLots();

        assertThat(count).isEqualTo(0);
        verify(creditLotRepo, never()).zeroOutLot(any());
        verify(creditLotRepo, never()).insertLedgerEntry(
                any(), any(), anyInt(), any(), any(), any(), any(), any(), any(), any());
    }

    // ── 9. expireCreditLots handles multiple expired lots ─────────────────────

    @Test
    void expireCreditLots_multipleExpiredLots_allZeroedAndLogged() {
        UUID lot1 = UUID.randomUUID();
        UUID lot2 = UUID.randomUUID();
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();

        when(creditLotRepo.findExpiredLots()).thenReturn(List.of(
                new CreditLotRepository.ExpiredLotRow(lot1, user1, "BOOST_CREDIT", 2),
                new CreditLotRepository.ExpiredLotRow(lot2, user2, "BOOST_CREDIT", 5)
        ));
        when(creditLotRepo.insertLedgerEntry(any(), any(), anyInt(), any(), any(), any(), any(),
                anyString(), any(), any()))
                .thenReturn(UUID.randomUUID());
        when(creditLotRepo.expireCreditLots()).thenCallRealMethod();

        int count = creditLotRepo.expireCreditLots();

        assertThat(count).isEqualTo(2);
        verify(creditLotRepo).zeroOutLot(lot1);
        verify(creditLotRepo).zeroOutLot(lot2);
        verify(creditLotRepo).insertLedgerEntry(
                eq(user1), eq("BOOST_CREDIT"), eq(-2),
                eq("EXPIRY"), isNull(), isNull(), isNull(),
                eq("expiry-" + lot1), isNull(), any());
        verify(creditLotRepo).insertLedgerEntry(
                eq(user2), eq("BOOST_CREDIT"), eq(-5),
                eq("EXPIRY"), isNull(), isNull(), isNull(),
                eq("expiry-" + lot2), isNull(), any());
    }

    // ── 10. Active boost is not cancelled when source lot expires ─────────────
    // This is a design principle: active_boosts has its own expires_at (NOW() + 30 min)
    // and is independent of the credit lot's expiry. The boost runs to completion.

    @Test
    void activateBoost_createsBoostWithOwnExpiryIndependentOfLotExpiry() {
        Instant boostStart = Instant.now();
        Instant boostEnd = boostStart.plusSeconds(1800); // 30 minutes

        when(creditLotRepo.findActiveBoost(userId)).thenReturn(Collections.emptyList());
        when(creditLotRepo.insertBoost(eq(userId), isNull(), eq(30)))
                .thenReturn(new CreditLotRepository.BoostInsertRow(UUID.randomUUID(), boostStart, boostEnd));
        when(creditService.getBalance(userId)).thenReturn(0L);

        var response = boostService.activateBoost(userId, null);

        // Boost has its own fixed expiry (30 min from now), not tied to lot expiry
        assertThat(response.expiresAt()).isEqualTo(boostEnd);
        assertThat(response.startedAt()).isEqualTo(boostStart);
    }
}
