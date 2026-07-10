package com.qaliye.backend.billing.service;

import com.qaliye.backend.billing.BillingProperties;
import com.qaliye.backend.billing.dto.BoostActivationResponse;
import com.qaliye.backend.billing.repository.CreditLotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class BoostService {

    private static final Logger log = LoggerFactory.getLogger(BoostService.class);

    private final CreditLotRepository creditLotRepo;
    private final BillingProperties billingProps;

    public BoostService(CreditLotRepository creditLotRepo, BillingProperties billingProps) {
        this.creditLotRepo = creditLotRepo;
        this.billingProps = billingProps;
    }

    @Transactional
    public BoostActivationResponse activateBoost(UUID userId, String idempotencyKey) {
        // Check for existing active boost
        List<CreditLotRepository.ActiveBoostRow> active = creditLotRepo.findActiveBoost(userId);
        if (!active.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "boost_already_active");
        }

        // Consume one BOOST_CREDIT via FIFO lot
        String idemKey = idempotencyKey != null ? idempotencyKey : "boost-" + UUID.randomUUID();

        UUID ledgerEntryId = creditLotRepo.insertLedgerEntry(
                userId, "BOOST_CREDIT", -1, "CONSUMPTION",
                null, null, null, idemKey, null, "{}"
        );
        if (ledgerEntryId == null) {
            // Idempotency collision — likely duplicate request; fetch active boost
            List<CreditLotRepository.ActiveBoostRow> existing = creditLotRepo.findActiveBoost(userId);
            if (!existing.isEmpty()) {
                var b = existing.get(0);
                int remaining = creditLotRepo.getBalance(userId, "BOOST_CREDIT");
                return new BoostActivationResponse(b.id(), b.startedAt(), b.expiresAt(), remaining);
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "duplicate_boost_request");
        }

        // Find and decrement a lot
        List<CreditLotRepository.LotRow> lots = creditLotRepo.findOldestValidLot(userId, "BOOST_CREDIT");
        if (lots.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "insufficient_boost_credits");
        }

        CreditLotRepository.LotRow lot = lots.get(0);
        int decremented = creditLotRepo.decrementLot(lot.id(), 1);
        if (decremented == 0) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "insufficient_boost_credits");
        }

        // Record consumption
        creditLotRepo.insertConsumption(ledgerEntryId, lot.id(), 1);

        // Create the boost
        CreditLotRepository.BoostInsertRow boost = creditLotRepo.insertBoost(
                userId, ledgerEntryId, billingProps.getBoostDurationMinutes());

        int remaining = creditLotRepo.getBalance(userId, "BOOST_CREDIT");
        log.info("Boost activated for user={}, boostId={}, expires={}", userId, boost.id(), boost.expiresAt());

        return new BoostActivationResponse(boost.id(), boost.startedAt(), boost.expiresAt(), remaining);
    }
}
