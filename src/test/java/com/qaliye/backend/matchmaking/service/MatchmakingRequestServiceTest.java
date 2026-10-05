package com.qaliye.backend.matchmaking.service;

import com.qaliye.backend.billing.repository.ActionLimitRepository;
import com.qaliye.backend.billing.service.ActionCostService;
import com.qaliye.backend.billing.service.CreditService;
import com.qaliye.backend.matchmaking.MatchmakingConstants;
import com.qaliye.backend.matchmaking.config.MatchmakingProperties;
import com.qaliye.backend.matchmaking.repository.MatchmakingIntroductionRepository;
import com.qaliye.backend.matchmaking.repository.MatchmakingIntroductionRepository.IntroductionRow;
import com.qaliye.backend.matchmaking.repository.MatchmakingPreferenceRepository;
import com.qaliye.backend.matchmaking.repository.MatchmakingPreferenceRepository.PreferenceRow;
import com.qaliye.backend.matchmaking.repository.MatchmakingRequestRepository;
import com.qaliye.backend.matchmaking.repository.MatchmakingRequestRepository.RequestRow;
import com.qaliye.backend.notifications.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MatchmakingRequestServiceTest {

    @Mock MatchmakingRequestRepository requestRepo;
    @Mock MatchmakingPreferenceRepository preferenceRepo;
    @Mock MatchmakingIntroductionRepository introductionRepo;
    @Mock ActionCostService actionCostService;
    @Mock ActionLimitRepository actionLimitRepo;
    @Mock CreditService creditService;
    @Mock NotificationDispatcher notificationDispatcher;

    MatchmakingProperties props = new MatchmakingProperties();
    MatchmakingRequestService service;

    UUID userId = UUID.randomUUID();
    UUID chargeKey = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    UUID prefId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new MatchmakingRequestService(requestRepo, preferenceRepo, introductionRepo,
                actionCostService, actionLimitRepo, creditService, notificationDispatcher, props);
    }

    /** Free allowance path — rule exists, limit not exhausted, no credits. */
    private ActionCostService.ActionCostResult freeAllowance() {
        return new ActionCostService.ActionCostResult(
                UUID.randomUUID(), 0, true, false, false,
                LocalDate.now(), LocalDate.now(), 0, 5, "MONTH");
    }

    private void stubSuccessfulCharge() {
        var cost = freeAllowance();
        when(actionCostService.evaluate(userId, MatchmakingConstants.ACTION_REQUEST))
                .thenReturn(cost);
        when(actionLimitRepo.ensureExists(userId, cost.ruleId(), cost.periodStart(), cost.periodEnd()))
                .thenReturn(Optional.empty());
        when(actionLimitRepo.tryIncrementUnderLimit(userId, cost.ruleId(), cost.periodStart(),
                cost.limitValue())).thenReturn(Optional.of(1));
    }

    // ── createRequest ────────────────────────────────────────────────────

    @Test
    void createRequest_idempotentRetry_returnsExistingRequest() {
        var existing = openRequestRow(requestId, userId);
        when(requestRepo.findByChargeKey(chargeKey)).thenReturn(Optional.of(existing));

        var result = service.createRequest(userId, chargeKey);

        assertThat(result.id()).isEqualTo(requestId);
        verify(requestRepo, never()).insert(any(), any(), any(), any());
    }

    @Test
    void createRequest_noPreferences_throws422() {
        when(requestRepo.findByChargeKey(chargeKey)).thenReturn(Optional.empty());
        when(preferenceRepo.findByUserId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createRequest(userId, chargeKey))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("preferences");
    }

    @Test
    void createRequest_alreadyHasActiveRequest_throws409() {
        when(requestRepo.findByChargeKey(chargeKey)).thenReturn(Optional.empty());
        when(preferenceRepo.findByUserId(userId)).thenReturn(Optional.of(prefRow()));
        when(requestRepo.findActiveByUserId(userId)).thenReturn(Optional.of(openRequestRow(UUID.randomUUID(), userId)));

        assertThatThrownBy(() -> service.createRequest(userId, chargeKey))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("active");
    }

    @Test
    void createRequest_success_chargesAndInsertsRequest() {
        when(requestRepo.findByChargeKey(chargeKey)).thenReturn(Optional.empty());
        when(preferenceRepo.findByUserId(userId)).thenReturn(Optional.of(prefRow()));
        when(requestRepo.findActiveByUserId(userId)).thenReturn(Optional.empty());
        stubSuccessfulCharge();
        when(requestRepo.insert(eq(userId), eq(prefId), eq(chargeKey), any()))
                .thenReturn(requestId);
        when(requestRepo.findById(requestId)).thenReturn(Optional.of(openRequestRow(requestId, userId)));

        var result = service.createRequest(userId, chargeKey);

        assertThat(result.id()).isEqualTo(requestId);
        verify(preferenceRepo).refreshGender(userId);
        verify(actionCostService).evaluate(userId, MatchmakingConstants.ACTION_REQUEST);
        verify(notificationDispatcher).dispatchMatchmakingRequestCreated(userId, requestId);
    }

    // ── cancelRequest ────────────────────────────────────────────────────

    @Test
    void cancelRequest_notOwner_throws403() {
        var request = openRequestRow(requestId, UUID.randomUUID());
        when(requestRepo.findById(requestId)).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.cancelRequest(requestId, userId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Access denied");
    }

    @Test
    void cancelRequest_notFound_throws404() {
        when(requestRepo.findById(requestId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelRequest(requestId, userId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void cancelRequest_alreadyTerminal_throws409() {
        var request = requestRow(requestId, userId, MatchmakingConstants.REQUEST_EXPIRED);
        when(requestRepo.findById(requestId)).thenReturn(Optional.of(request));
        when(requestRepo.cancel(requestId)).thenReturn(false);

        assertThatThrownBy(() -> service.cancelRequest(requestId, userId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("cannot be cancelled");
    }

    @Test
    void cancelRequest_success_cancelsRequest() {
        var request = openRequestRow(requestId, userId);
        when(requestRepo.findById(requestId)).thenReturn(Optional.of(request));
        when(requestRepo.cancel(requestId)).thenReturn(true);
        when(introductionRepo.findActiveByRequestId(requestId)).thenReturn(Optional.empty());

        service.cancelRequest(requestId, userId);

        verify(requestRepo).cancel(requestId);
    }

    @Test
    void cancelRequest_onHoldWithActiveIntro_cascadesAndReleasesPartner() {
        UUID partnerRequestId = UUID.randomUUID();
        UUID introId = UUID.randomUUID();
        var request = requestRow(requestId, userId, MatchmakingConstants.REQUEST_ON_HOLD);
        var intro = new IntroductionRow(introId, requestId, partnerRequestId,
                UUID.randomUUID(),
                MatchmakingConstants.DECISION_PENDING, MatchmakingConstants.DECISION_PENDING,
                MatchmakingConstants.INTRO_PROPOSED,
                0, 0, 0, null, null,
                null, null, OffsetDateTime.now().plusHours(72), null,
                OffsetDateTime.now(), null, OffsetDateTime.now(), OffsetDateTime.now());

        when(requestRepo.findById(requestId)).thenReturn(Optional.of(request));
        when(requestRepo.cancel(requestId)).thenReturn(true);
        when(introductionRepo.findActiveByRequestId(requestId)).thenReturn(Optional.of(intro));

        service.cancelRequest(requestId, userId);

        verify(introductionRepo).cancel(introId);
        verify(requestRepo).releaseOnHold(requestId, partnerRequestId);
    }

    // ── expireOpenRequests ────────────────────────────────────────────────

    @Test
    void expireOpenRequests_returnsExpiredIds() {
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();

        when(requestRepo.expireOpenRequests()).thenReturn(List.of(idA, idB));

        var expired = service.expireOpenRequests();

        assertThat(expired).containsExactlyInAnyOrder(idA, idB);
    }

    @Test
    void expireOpenRequests_noneExpired_returnsEmpty() {
        when(requestRepo.expireOpenRequests()).thenReturn(List.of());

        assertThat(service.expireOpenRequests()).isEmpty();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private RequestRow openRequestRow(UUID id, UUID uid) {
        return requestRow(id, uid, MatchmakingConstants.REQUEST_OPEN);
    }

    private RequestRow requestRow(UUID id, UUID uid, String status) {
        return new RequestRow(id, uid, prefId, status,
                chargeKey, null, OffsetDateTime.now(), null, null,
                OffsetDateTime.now().plusDays(90), OffsetDateTime.now(), null);
    }

    private PreferenceRow prefRow() {
        return new PreferenceRow(prefId, userId, 1, "FEMALE",
                null, null, null, null,
                new String[0], "any", "any",
                new String[0], new String[0], new String[0],
                new String[0], new String[0],
                new UUID[0], new UUID[0],
                null, null,
                null, null, null, null, null,
                OffsetDateTime.now(), OffsetDateTime.now());
    }
}
