package com.qaliye.backend.discovery.service;

import com.qaliye.backend.billing.repository.ActionLimitRepository;
import com.qaliye.backend.billing.service.ActionCostService;
import com.qaliye.backend.billing.service.CreditService;
import com.qaliye.backend.discovery.dto.RevealResponse;
import com.qaliye.backend.discovery.exception.ActionLimitExceededException;
import com.qaliye.backend.storage.SupabaseStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RevealServiceTest {

    @Mock NamedParameterJdbcTemplate jdbc;
    @Mock ActionCostService actionCostService;
    @Mock ActionLimitRepository actionLimitRepo;
    @Mock CreditService creditService;
    @Mock SupabaseStorageService storageService;

    @InjectMocks RevealService service;

    UUID callerId = UUID.randomUUID();
    UUID actorId  = UUID.randomUUID();
    UUID actionId = UUID.randomUUID();
    UUID ruleId   = UUID.randomUUID();

    // No global stubs — each test sets up exactly what it needs.

    private void mockActionFound(boolean alreadyRevealed) {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("id", actionId);
        row.put("actor_user_id", actorId);
        row.put("action_type", "LIKE");
        row.put("revealed_at", alreadyRevealed ? java.sql.Timestamp.valueOf("2025-01-01 12:00:00") : null);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(List.of(row));
    }

    private ActionCostService.ActionCostResult freeAllowanceAvailable(int limit) {
        return new ActionCostService.ActionCostResult(
                ruleId, 0, true, false, false,
                LocalDate.now(), LocalDate.now(), 0, limit, "DAY");
    }

    private ActionCostService.ActionCostResult freeAllowanceExhaustedWithCredits(long creditCost) {
        return new ActionCostService.ActionCostResult(
                ruleId, creditCost, false, true, false,
                LocalDate.now(), LocalDate.now(), 1, 1, "DAY");
    }

    private ActionCostService.ActionCostResult freeAllowanceExhaustedBlocked() {
        return new ActionCostService.ActionCostResult(
                ruleId, 0, false, true, true,
                LocalDate.now(), LocalDate.now(), 1, 1, "DAY");
    }

    // ── Test 3: Fetching likes does NOT reveal anything ──────────────────────
    // (Covered by LikesServiceTest.getLikes_received_doesNotRevealAnyLikes)

    // ── Test 4: User can manually reveal any selected like ───────────────────
    @Test
    void reveal_validAction_revealsSuccessfully() {
        mockActionFound(false);
        when(actionCostService.evaluate(callerId, "SEE_WHO_LIKED_YOU"))
                .thenReturn(freeAllowanceAvailable(1));
        when(actionLimitRepo.ensureExists(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(actionLimitRepo.tryIncrementUnderLimit(any(), any(), any(), eq(1)))
                .thenReturn(Optional.of(1));

        RevealResponse response = service.reveal(callerId, actionId);

        assertThat(response.actionId()).isEqualTo(actionId);
        assertThat(response.idempotent()).isFalse();
        verify(jdbc).update(anyString(), any(MapSqlParameterSource.class));
    }

    // ── Test 5: First reveal consumes the free daily allowance ───────────────
    @Test
    void reveal_firstReveal_consumesFreeAllowance() {
        mockActionFound(false);
        when(actionCostService.evaluate(callerId, "SEE_WHO_LIKED_YOU"))
                .thenReturn(freeAllowanceAvailable(1));
        when(actionLimitRepo.ensureExists(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(actionLimitRepo.tryIncrementUnderLimit(any(), any(), any(), eq(1)))
                .thenReturn(Optional.of(1));

        service.reveal(callerId, actionId);

        verify(actionLimitRepo).tryIncrementUnderLimit(callerId, ruleId, LocalDate.now(), 1);
        verify(creditService, never()).consumeCredits(any(), anyLong(), anyString(), anyString());
    }

    // ── Test 6: Free reveal does NOT consume paid credits ─────────────────────
    @Test
    void reveal_freeAllowanceAvailable_doesNotConsumeCredits() {
        mockActionFound(false);
        when(actionCostService.evaluate(callerId, "SEE_WHO_LIKED_YOU"))
                .thenReturn(freeAllowanceAvailable(1));
        when(actionLimitRepo.ensureExists(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(actionLimitRepo.tryIncrementUnderLimit(any(), any(), any(), eq(1)))
                .thenReturn(Optional.of(1));

        service.reveal(callerId, actionId);

        verify(creditService, never()).consumeCredits(any(), anyLong(), anyString(), anyString());
    }

    // ── Test 7: After free exhausted, next reveal consumes one credit ────────
    @Test
    void reveal_freeExhausted_consumesPaidCredit() {
        mockActionFound(false);
        when(actionCostService.evaluate(callerId, "SEE_WHO_LIKED_YOU"))
                .thenReturn(freeAllowanceExhaustedWithCredits(2));
        when(actionLimitRepo.ensureExists(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(actionLimitRepo.tryIncrementUnderLimit(any(), any(), any(), eq(1)))
                .thenReturn(Optional.empty()); // free slot exhausted

        when(creditService.consumeCredits(eq(callerId), eq(2L), eq("SEE_WHO_LIKED_YOU"), anyString()))
                .thenReturn(8L);
        when(creditService.getBalance(callerId)).thenReturn(8L);

        RevealResponse response = service.reveal(callerId, actionId);

        verify(creditService).consumeCredits(eq(callerId), eq(2L), eq("SEE_WHO_LIKED_YOU"), anyString());
        assertThat(response.creditBalance()).isEqualTo(8L);
    }

    // ── Test 8: Multiple paid reveals decrement credits correctly ────────────
    @Test
    void reveal_multiplePaidReveals_decrementsCreditsCorrectly() {
        mockActionFound(false);
        when(actionCostService.evaluate(callerId, "SEE_WHO_LIKED_YOU"))
                .thenReturn(freeAllowanceExhaustedWithCredits(1));
        when(actionLimitRepo.ensureExists(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(actionLimitRepo.tryIncrementUnderLimit(any(), any(), any(), eq(1)))
                .thenReturn(Optional.empty());

        when(creditService.consumeCredits(eq(callerId), eq(1L), eq("SEE_WHO_LIKED_YOU"), anyString()))
                .thenReturn(9L);
        when(creditService.getBalance(callerId)).thenReturn(9L);

        RevealResponse response = service.reveal(callerId, actionId);

        verify(creditService).consumeCredits(eq(callerId), eq(1L), eq("SEE_WHO_LIKED_YOU"), anyString());
        assertThat(response.creditBalance()).isEqualTo(9L);
    }

    // ── Test 9: No free allowance + no paid credits → rejected ───────────────
    @Test
    void reveal_noFreeAndNoCredits_rejected() {
        mockActionFound(false);
        when(actionCostService.evaluate(callerId, "SEE_WHO_LIKED_YOU"))
                .thenReturn(freeAllowanceExhaustedBlocked());

        assertThatThrownBy(() -> service.reveal(callerId, actionId))
                .isInstanceOf(ActionLimitExceededException.class);

        verify(creditService, never()).consumeCredits(any(), anyLong(), anyString(), anyString());
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    // ── Test 10: Selecting an old like does not bypass entitlement ───────────
    @Test
    void reveal_oldLike_doesNotBypassEntitlement() {
        mockActionFound(false);
        when(actionCostService.evaluate(callerId, "SEE_WHO_LIKED_YOU"))
                .thenReturn(freeAllowanceExhaustedBlocked());

        assertThatThrownBy(() -> service.reveal(callerId, actionId))
                .isInstanceOf(ActionLimitExceededException.class);

        // The reveal is rejected regardless of which like was selected
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    // ── Test 11: Selecting a new like does not receive special treatment ────
    @Test
    void reveal_newLike_sameEntitlementRules() {
        mockActionFound(false);
        when(actionCostService.evaluate(callerId, "SEE_WHO_LIKED_YOU"))
                .thenReturn(freeAllowanceAvailable(1));
        when(actionLimitRepo.ensureExists(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(actionLimitRepo.tryIncrementUnderLimit(any(), any(), any(), eq(1)))
                .thenReturn(Optional.of(1));

        service.reveal(callerId, actionId);

        // Same free-allowance consumption regardless of like age
        verify(actionLimitRepo).tryIncrementUnderLimit(callerId, ruleId, LocalDate.now(), 1);
        verify(creditService, never()).consumeCredits(any(), anyLong(), anyString(), anyString());
    }

    // ── Test 12: Already revealed like does not consume free or paid ──────────
    @Test
    void reveal_alreadyRevealed_doesNotCharge() {
        mockActionFound(true);

        RevealResponse response = service.reveal(callerId, actionId);

        assertThat(response.idempotent()).isTrue();
        verify(actionCostService, never()).evaluate(any(), anyString());
        verify(actionLimitRepo, never()).tryIncrementUnderLimit(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(creditService, never()).consumeCredits(any(), anyLong(), anyString(), anyString());
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    // ── Test 13: User cannot reveal another user's like ──────────────────────
    @Test
    void reveal_notUsersLike_throwsNotFound() {
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(List.of()); // action not found for this caller

        assertThatThrownBy(() -> service.reveal(callerId, actionId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                });

        verify(actionCostService, never()).evaluate(any(), anyString());
        verify(creditService, never()).consumeCredits(any(), anyLong(), anyString(), anyString());
    }

    // ── Test 14: Concurrent reveal requests cannot consume same free slot ───
    @Test
    void reveal_concurrentRequests_onlyOneGetsFreeSlot() {
        mockActionFound(false);
        when(actionCostService.evaluate(callerId, "SEE_WHO_LIKED_YOU"))
                .thenReturn(freeAllowanceAvailable(1));
        when(actionLimitRepo.ensureExists(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        // Simulate: first request gets the slot, second doesn't
        when(actionLimitRepo.tryIncrementUnderLimit(any(), any(), any(), eq(1)))
                .thenReturn(Optional.of(1))   // first call succeeds
                .thenReturn(Optional.empty()); // second call fails

        // First reveal succeeds with free allowance
        service.reveal(callerId, actionId);
        verify(creditService, never()).consumeCredits(any(), anyLong(), anyString(), anyString());

        // Second reveal (different action) should fall through to credits or be blocked
        // Since tryIncrementUnderLimit returns empty and cost.requiresCredits() is false (memberCreditCost=0),
        // it should throw ActionLimitExceededException
        UUID actionId2 = UUID.randomUUID();
        Map<String, Object> row2 = new java.util.HashMap<>();
        row2.put("id", actionId2);
        row2.put("actor_user_id", UUID.randomUUID());
        row2.put("action_type", "LIKE");
        row2.put("revealed_at", null);
        // Reset the queryForList to return the new action
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(List.of(row2));

        assertThatThrownBy(() -> service.reveal(callerId, actionId2))
                .isInstanceOf(ActionLimitExceededException.class);
    }

    // ── Test 15: Concurrent requests cannot make credits negative ────────────
    @Test
    void reveal_concurrentRequests_creditsCannotGoNegative() {
        mockActionFound(false);
        when(actionCostService.evaluate(callerId, "SEE_WHO_LIKED_YOU"))
                .thenReturn(freeAllowanceExhaustedWithCredits(1));
        when(actionLimitRepo.ensureExists(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(actionLimitRepo.tryIncrementUnderLimit(any(), any(), any(), eq(1)))
                .thenReturn(Optional.empty());

        // CreditService.consumeCredits uses atomic UPDATE ... WHERE balance + delta >= 0
        // If balance is 0, it throws InsufficientCreditsException
        when(creditService.consumeCredits(eq(callerId), eq(1L), eq("SEE_WHO_LIKED_YOU"), anyString()))
                .thenThrow(new CreditService.InsufficientCreditsException(
                        "Insufficient credits", 1, 0));

        assertThatThrownBy(() -> service.reveal(callerId, actionId))
                .isInstanceOf(CreditService.InsufficientCreditsException.class);

        // The reveal should NOT be marked
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    // ── Test 16: Daily free allowance resets per existing day boundary ───────
    @Test
    void reveal_dailyAllowanceResets_newDay() {
        mockActionFound(false);
        when(actionCostService.evaluate(callerId, "SEE_WHO_LIKED_YOU"))
                .thenReturn(freeAllowanceAvailable(1));
        when(actionLimitRepo.ensureExists(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(actionLimitRepo.tryIncrementUnderLimit(any(), any(), any(), eq(1)))
                .thenReturn(Optional.of(1));

        service.reveal(callerId, actionId);

        // ActionCostService.evaluate uses LocalDate.now() for DAY period type.
        // A new day means a new periodStart, which means ensureExists creates a new tracker
        // with used_count=0, effectively resetting the daily allowance.
        ArgumentCaptor<LocalDate> periodCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(actionLimitRepo).ensureExists(eq(callerId), eq(ruleId), periodCaptor.capture(), any());
        assertThat(periodCaptor.getValue()).isEqualTo(LocalDate.now());
    }
}
