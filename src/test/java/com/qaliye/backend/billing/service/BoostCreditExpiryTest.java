package com.qaliye.backend.billing.service;

import com.qaliye.backend.billing.BillingProperties;
import com.qaliye.backend.billing.repository.CreditLotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
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
    BillingProperties billingProps;
    BoostService boostService;

    UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        billingProps = new BillingProperties();
        billingProps.setBoostDurationMinutes(30);
        boostService = new BoostService(creditLotRepo, billingProps);
    }

    // ── 1. Purchased Boost pack: expires_at = NULL, credits never expire ──────

    @Test
    void activateBoost_purchasedPack_nullExpiry_consumedSuccessfully() {
        UUID lotId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        Instant start = Instant.now();
        Instant end = start.plusSeconds(1800);

        when(creditLotRepo.findActiveBoost(userId)).thenReturn(Collections.emptyList());
        when(creditLotRepo.insertLedgerEntry(eq(userId), eq("BOOST_CREDIT"), eq(-1),
                eq("CONSUMPTION"), any(), any(), any(), anyString(), any(), any()))
                .thenReturn(ledgerId);
        // Lot with null expiry is still valid (expires_at IS NULL OR expires_at > NOW())
        when(creditLotRepo.findOldestValidLot(userId, "BOOST_CREDIT"))
                .thenReturn(List.of(new CreditLotRepository.LotRow(lotId, 5)));
        when(creditLotRepo.decrementLot(lotId, 1)).thenReturn(1);
        when(creditLotRepo.insertBoost(eq(userId), eq(ledgerId), eq(30)))
                .thenReturn(new CreditLotRepository.BoostInsertRow(UUID.randomUUID(), start, end));
        when(creditLotRepo.getBalance(userId, "BOOST_CREDIT")).thenReturn(4);

        var response = boostService.activateBoost(userId, null);

        assertThat(response.creditsRemaining()).isEqualTo(4);
        verify(creditLotRepo).decrementLot(lotId, 1);
    }

    // ── 2. Premium subscription allowance: expires_at = periodEnd ─────────────

    @Test
    void activateBoost_premiumAllowance_withExpiry_consumedSuccessfully() {
        UUID lotId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        Instant start = Instant.now();
        Instant end = start.plusSeconds(1800);

        when(creditLotRepo.findActiveBoost(userId)).thenReturn(Collections.emptyList());
        when(creditLotRepo.insertLedgerEntry(eq(userId), eq("BOOST_CREDIT"), eq(-1),
                eq("CONSUMPTION"), any(), any(), any(), anyString(), any(), any()))
                .thenReturn(ledgerId);
        // Lot with future expiry is valid
        when(creditLotRepo.findOldestValidLot(userId, "BOOST_CREDIT"))
                .thenReturn(List.of(new CreditLotRepository.LotRow(lotId, 1)));
        when(creditLotRepo.decrementLot(lotId, 1)).thenReturn(1);
        when(creditLotRepo.insertBoost(eq(userId), eq(ledgerId), eq(30)))
                .thenReturn(new CreditLotRepository.BoostInsertRow(UUID.randomUUID(), start, end));
        when(creditLotRepo.getBalance(userId, "BOOST_CREDIT")).thenReturn(0);

        var response = boostService.activateBoost(userId, null);

        assertThat(response.creditsRemaining()).isEqualTo(0);
        verify(creditLotRepo).decrementLot(lotId, 1);
    }

    // ── 3. Promotional/admin-granted credits: expires_at = campaign expiry ───

    @Test
    void activateBoost_promotionalCredit_withExpiry_consumedSuccessfully() {
        UUID lotId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();

        when(creditLotRepo.findActiveBoost(userId)).thenReturn(Collections.emptyList());
        when(creditLotRepo.insertLedgerEntry(eq(userId), eq("BOOST_CREDIT"), eq(-1),
                eq("CONSUMPTION"), any(), any(), any(), anyString(), any(), any()))
                .thenReturn(ledgerId);
        when(creditLotRepo.findOldestValidLot(userId, "BOOST_CREDIT"))
                .thenReturn(List.of(new CreditLotRepository.LotRow(lotId, 2)));
        when(creditLotRepo.decrementLot(lotId, 1)).thenReturn(1);
        when(creditLotRepo.insertBoost(eq(userId), eq(ledgerId), eq(30)))
                .thenReturn(new CreditLotRepository.BoostInsertRow(
                        UUID.randomUUID(), Instant.now(), Instant.now().plusSeconds(1800)));
        when(creditLotRepo.getBalance(userId, "BOOST_CREDIT")).thenReturn(1);

        var response = boostService.activateBoost(userId, null);

        assertThat(response.creditsRemaining()).isEqualTo(1);
    }

    // ── 4. Expired lots are ignored during activation ─────────────────────────

    @Test
    void activateBoost_allLotsExpired_throwsInsufficientCredits() {
        UUID ledgerId = UUID.randomUUID();

        when(creditLotRepo.findActiveBoost(userId)).thenReturn(Collections.emptyList());
        when(creditLotRepo.insertLedgerEntry(eq(userId), eq("BOOST_CREDIT"), eq(-1),
                eq("CONSUMPTION"), any(), any(), any(), anyString(), any(), any()))
                .thenReturn(ledgerId);
        // findOldestValidLot returns empty because expired lots are filtered by SQL
        // (expires_at IS NULL OR expires_at > NOW())
        when(creditLotRepo.findOldestValidLot(userId, "BOOST_CREDIT"))
                .thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> boostService.activateBoost(userId, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("insufficient_boost_credits");
    }

    // ── 5. Earliest-expiry lot is consumed first ──────────────────────────────
    // This is verified at the SQL level (ORDER BY expires_at ASC NULLS LAST, created_at ASC)
    // Here we verify the service correctly uses the lot returned by findOldestValidLot

    @Test
    void activateBoost_earliestExpiryLotConsumedFirst() {
        UUID earliestExpiryLotId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();

        when(creditLotRepo.findActiveBoost(userId)).thenReturn(Collections.emptyList());
        when(creditLotRepo.insertLedgerEntry(eq(userId), eq("BOOST_CREDIT"), eq(-1),
                eq("CONSUMPTION"), any(), any(), any(), anyString(), any(), any()))
                .thenReturn(ledgerId);
        // Repository returns the earliest-expiry lot first (SQL handles ordering)
        when(creditLotRepo.findOldestValidLot(userId, "BOOST_CREDIT"))
                .thenReturn(List.of(new CreditLotRepository.LotRow(earliestExpiryLotId, 3)));
        when(creditLotRepo.decrementLot(earliestExpiryLotId, 1)).thenReturn(1);
        when(creditLotRepo.insertBoost(eq(userId), eq(ledgerId), eq(30)))
                .thenReturn(new CreditLotRepository.BoostInsertRow(
                        UUID.randomUUID(), Instant.now(), Instant.now().plusSeconds(1800)));
        when(creditLotRepo.getBalance(userId, "BOOST_CREDIT")).thenReturn(2);

        boostService.activateBoost(userId, null);

        // Verify the earliest-expiry lot was the one decremented
        verify(creditLotRepo).decrementLot(earliestExpiryLotId, 1);
    }

    // ── 6. Non-expiring lots consumed oldest-first ────────────────────────────
    // SQL: ORDER BY expires_at ASC NULLS LAST, created_at ASC
    // Among non-expiring (NULL) lots, oldest created_at wins

    @Test
    void activateBoost_nonExpiringLots_oldestCreatedFirst() {
        UUID oldestLotId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();

        when(creditLotRepo.findActiveBoost(userId)).thenReturn(Collections.emptyList());
        when(creditLotRepo.insertLedgerEntry(eq(userId), eq("BOOST_CREDIT"), eq(-1),
                eq("CONSUMPTION"), any(), any(), any(), anyString(), any(), any()))
                .thenReturn(ledgerId);
        // Repository returns oldest non-expiring lot first (SQL handles NULLS LAST, created_at ASC)
        when(creditLotRepo.findOldestValidLot(userId, "BOOST_CREDIT"))
                .thenReturn(List.of(new CreditLotRepository.LotRow(oldestLotId, 2)));
        when(creditLotRepo.decrementLot(oldestLotId, 1)).thenReturn(1);
        when(creditLotRepo.insertBoost(eq(userId), eq(ledgerId), eq(30)))
                .thenReturn(new CreditLotRepository.BoostInsertRow(
                        UUID.randomUUID(), Instant.now(), Instant.now().plusSeconds(1800)));
        when(creditLotRepo.getBalance(userId, "BOOST_CREDIT")).thenReturn(1);

        boostService.activateBoost(userId, null);

        verify(creditLotRepo).decrementLot(oldestLotId, 1);
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
        UUID lotId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        Instant boostStart = Instant.now();
        Instant boostEnd = boostStart.plusSeconds(1800); // 30 minutes

        when(creditLotRepo.findActiveBoost(userId)).thenReturn(Collections.emptyList());
        when(creditLotRepo.insertLedgerEntry(eq(userId), eq("BOOST_CREDIT"), eq(-1),
                eq("CONSUMPTION"), any(), any(), any(), anyString(), any(), any()))
                .thenReturn(ledgerId);
        when(creditLotRepo.findOldestValidLot(userId, "BOOST_CREDIT"))
                .thenReturn(List.of(new CreditLotRepository.LotRow(lotId, 1)));
        when(creditLotRepo.decrementLot(lotId, 1)).thenReturn(1);
        // insertBoost creates active_boosts with NOW() + 30 min, independent of lot expiry
        when(creditLotRepo.insertBoost(eq(userId), eq(ledgerId), eq(30)))
                .thenReturn(new CreditLotRepository.BoostInsertRow(UUID.randomUUID(), boostStart, boostEnd));
        when(creditLotRepo.getBalance(userId, "BOOST_CREDIT")).thenReturn(0);

        var response = boostService.activateBoost(userId, null);

        // Boost has its own fixed expiry (30 min from now), not tied to lot expiry
        assertThat(response.expiresAt()).isEqualTo(boostEnd);
        assertThat(response.startedAt()).isEqualTo(boostStart);
    }
}
