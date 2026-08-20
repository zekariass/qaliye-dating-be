package com.qaliye.backend.billing.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Manages the central credit balance for each user.
 *
 * Responsibilities:
 *  - Maintain user_credit_balances (one row per user, non-negative)
 *  - Write immutable entries to user_credit_ledger
 *  - Create and decrement user_entitlement_credit_lots (credit_source_type based)
 *  - Record lot-allocation details in user_credit_lot_consumptions
 *
 * All balance-changing operations are transactional and concurrency-safe.
 */
@Service
public class CreditService {

    private static final Logger log = LoggerFactory.getLogger(CreditService.class);

    private final NamedParameterJdbcTemplate jdbc;

    public CreditService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Balance queries ─────────────────────────────────────────────────────

    private static final String GET_BALANCE_SQL = """
            SELECT balance FROM user_credit_balances WHERE user_id = :userId FOR UPDATE
            """;

    private static final String GET_BALANCE_READONLY_SQL = """
            SELECT COALESCE(balance, 0) FROM user_credit_balances WHERE user_id = :userId
            """;

    private static final String UPSERT_BALANCE_SQL = """
            INSERT INTO user_credit_balances (user_id, balance)
            VALUES (:userId, 0)
            ON CONFLICT (user_id) DO NOTHING
            """;

    private static final String UPDATE_BALANCE_SQL = """
            UPDATE user_credit_balances
            SET balance    = balance + :delta,
                updated_at = CURRENT_TIMESTAMP
            WHERE user_id = :userId
              AND balance + :delta >= 0
            RETURNING balance
            """;

    public long getBalance(UUID userId) {
        var params = new MapSqlParameterSource("userId", userId);
        try {
            Long b = jdbc.queryForObject(GET_BALANCE_READONLY_SQL, params, Long.class);
            return b != null ? b : 0L;
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return 0L;
        }
    }

    public void ensureBalanceRowExists(UUID userId) {
        jdbc.update(UPSERT_BALANCE_SQL, new MapSqlParameterSource("userId", userId));
    }

    // ── Ledger entry ────────────────────────────────────────────────────────

    private static final String INSERT_LEDGER_SQL = """
            INSERT INTO user_credit_ledger
                (user_id, transaction_type, amount, balance_after,
                 source_type, source_id, action_type, idempotency_key)
            VALUES
                (:userId, :transactionType, :amount, :balanceAfter,
                 :sourceType, :sourceId, :actionType, :idempotencyKey)
            ON CONFLICT (user_id, idempotency_key)
                WHERE idempotency_key IS NOT NULL
            DO NOTHING
            RETURNING id
            """;

    private UUID insertLedgerEntry(UUID userId, String transactionType, long amount,
                                   long balanceAfter, String sourceType, UUID sourceId,
                                   String actionType, String idempotencyKey) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("transactionType", transactionType)
                .addValue("amount", amount)
                .addValue("balanceAfter", balanceAfter)
                .addValue("sourceType", sourceType)
                .addValue("sourceId", sourceId)
                .addValue("actionType", actionType)
                .addValue("idempotencyKey", idempotencyKey);
        var rows = jdbc.query(INSERT_LEDGER_SQL, params, (rs, rn) -> rs.getObject("id", UUID.class));
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── Credit lot (new-style, credit_source_type based) ───────────────────

    private static final String INSERT_CREDIT_LOT_SQL = """
            INSERT INTO user_entitlement_credit_lots
                (user_id, entitlement_type, credit_source_type,
                 source_ledger_entry_id, quantity_granted, quantity_remaining, expires_at)
            VALUES
                (:userId, 'CREDIT_PACKAGE', :creditSourceType,
                 :ledgerEntryId, :granted, :granted, :expiresAt)
            RETURNING id
            """;

    private UUID insertCreditLot(UUID userId, String creditSourceType, UUID ledgerEntryId,
                                 long granted, Instant expiresAt) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("creditSourceType", creditSourceType)
                .addValue("ledgerEntryId", ledgerEntryId)
                .addValue("granted", granted)
                .addValue("expiresAt", expiresAt != null ? java.sql.Timestamp.from(expiresAt) : null);
        var rows = jdbc.query(INSERT_CREDIT_LOT_SQL, params, (rs, rn) -> rs.getObject("id", UUID.class));
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── Lot consumption record ──────────────────────────────────────────────

    private static final String INSERT_LOT_CONSUMPTION_SQL = """
            INSERT INTO user_credit_lot_consumptions
                (user_id, credit_lot_id, ledger_entry_id, amount)
            VALUES
                (:userId, :lotId, :ledgerEntryId, :amount)
            """;

    private void insertLotConsumption(UUID userId, UUID lotId, UUID ledgerEntryId, long amount) {
        jdbc.update(INSERT_LOT_CONSUMPTION_SQL, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("lotId", lotId)
                .addValue("ledgerEntryId", ledgerEntryId)
                .addValue("amount", amount));
    }

    // ── Subscription allowance expiry ───────────────────────────────────────

    private static final String FIND_SUB_ALLOWANCE_LOTS_SQL = """
            SELECT id, quantity_remaining
            FROM user_entitlement_credit_lots
            WHERE user_id = :userId
              AND credit_source_type = 'SUBSCRIPTION_ALLOWANCE'
              AND quantity_remaining > 0
            FOR UPDATE
            """;

    private static final String ZERO_SUB_ALLOWANCE_LOTS_SQL = """
            UPDATE user_entitlement_credit_lots
            SET quantity_remaining = 0
            WHERE user_id = :userId
              AND credit_source_type = 'SUBSCRIPTION_ALLOWANCE'
              AND quantity_remaining > 0
            """;

    /**
     * Expires all remaining SUBSCRIPTION_ALLOWANCE credit lots for a user, deducting
     * the total from the central balance and writing an EXPIRATION ledger entry.
     * Called before granting new subscription period credits to prevent accumulation
     * across periods.  Purchased credits (CREDIT_PURCHASE) are NOT affected.
     * <p>Idempotent: a duplicate call with the same idempotencyKey is a no-op.</p>
     *
     * @param userId          the user whose subscription allowance credits should expire
     * @param subscriptionId  internal subscription UUID for audit (may be null)
     * @param idempotencyKey  prevents double-expiry on retries
     * @return true if credits were expired, false if already processed or nothing to expire
     */
    @Transactional
    public boolean expireSubscriptionAllowanceLots(UUID userId, UUID subscriptionId, String idempotencyKey) {
        // Idempotency: check before acquiring any locks (common fast-path)
        Boolean alreadyDone = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM user_credit_ledger "
                        + "WHERE user_id = :userId AND idempotency_key = :key)",
                new MapSqlParameterSource().addValue("userId", userId).addValue("key", idempotencyKey),
                Boolean.class);
        if (Boolean.TRUE.equals(alreadyDone)) {
            log.info("expireSubscriptionAllowanceLots: already processed key={}", idempotencyKey);
            return false;
        }

        // Acquire balance row lock first — matches lock order in consumeCredits to prevent deadlocks
        List<Long> balanceRows = jdbc.query(
                "SELECT balance FROM user_credit_balances WHERE user_id = :userId FOR UPDATE",
                new MapSqlParameterSource("userId", userId),
                (rs, rn) -> rs.getLong("balance"));
        if (balanceRows.isEmpty()) {
            log.debug("expireSubscriptionAllowanceLots: no balance row for user={}, nothing to expire", userId);
            return false;
        }
        long currentBalance = balanceRows.get(0);

        // Lock and collect SUBSCRIPTION_ALLOWANCE lots
        List<Object[]> lots = new ArrayList<>();
        jdbc.query(FIND_SUB_ALLOWANCE_LOTS_SQL,
                new MapSqlParameterSource("userId", userId),
                (rs, rn) -> {
                    lots.add(new Object[]{rs.getObject("id", UUID.class), rs.getLong("quantity_remaining")});
                    return null;
                });

        long totalToExpire = lots.stream().mapToLong(a -> (Long) a[1]).sum();
        if (totalToExpire <= 0) {
            log.debug("expireSubscriptionAllowanceLots: no credits to expire for user={}", userId);
            return false;
        }

        // Zero out all SUBSCRIPTION_ALLOWANCE lots (rows already locked above)
        jdbc.update(ZERO_SUB_ALLOWANCE_LOTS_SQL, new MapSqlParameterSource("userId", userId));

        // Deduct from central balance (balance row already locked; GREATEST guards inconsistency)
        long newBalance = Math.max(0L, currentBalance - totalToExpire);
        jdbc.update("UPDATE user_credit_balances "
                        + "SET balance = :balance, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE user_id = :userId",
                new MapSqlParameterSource().addValue("userId", userId).addValue("balance", newBalance));

        // Immutable audit entry in user_credit_ledger
        insertLedgerEntry(userId, "EXPIRATION", -totalToExpire, newBalance,
                "SUBSCRIPTION", subscriptionId, null, idempotencyKey);

        log.info("Expired subscription allowance: user={}, amount={}, newBalance={}", userId, totalToExpire, newBalance);
        return true;
    }

    // ── Find lots for FIFO consumption ──────────────────────────────────────

    private static final String FIND_LOTS_FOR_CONSUMPTION_SQL = """
            SELECT id, quantity_remaining
            FROM user_entitlement_credit_lots
            WHERE user_id = :userId
              AND credit_source_type IS NOT NULL
              AND quantity_remaining > 0
              AND (expires_at IS NULL OR expires_at > NOW())
            ORDER BY
                expires_at IS NULL,
                expires_at,
                created_at,
                id
            FOR UPDATE
            """;

    private static final String DECREMENT_LOT_SQL = """
            UPDATE user_entitlement_credit_lots
            SET quantity_remaining = quantity_remaining - :amount
            WHERE id = :lotId
              AND quantity_remaining >= :amount
            """;

    // ── Public grant operations ─────────────────────────────────────────────

    /**
     * Grants subscription allowance credits. Creates a ledger entry, a credit lot, and
     * updates the central balance. Idempotent on idempotencyKey.
     *
     * @param userId           recipient
     * @param amount           credits to grant
     * @param subscriptionId   source subscription ID (for audit)
     * @param idempotencyKey   prevents duplicate grants on retries
     * @param expiresAt        when these allowance credits expire (null = never)
     * @return true if credits were granted, false if duplicate (already processed)
     */
    @Transactional
    public boolean grantSubscriptionAllowance(UUID userId, long amount,
                                              UUID subscriptionId, String idempotencyKey,
                                              Instant expiresAt) {
        if (amount <= 0) return true;
        ensureBalanceRowExists(userId);

        Long newBalance = jdbc.queryForObject(
                "UPDATE user_credit_balances SET balance = balance + :amount, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE user_id = :userId RETURNING balance",
                new MapSqlParameterSource().addValue("userId", userId).addValue("amount", amount),
                Long.class);
        if (newBalance == null) return false;

        UUID ledgerEntryId = insertLedgerEntry(userId, "SUBSCRIPTION_ALLOWANCE", amount,
                newBalance, "SUBSCRIPTION", subscriptionId, null, idempotencyKey);
        if (ledgerEntryId == null) {
            // Idempotency key already exists — roll back balance change
            jdbc.update("UPDATE user_credit_balances SET balance = balance - :amount, "
                            + "updated_at = CURRENT_TIMESTAMP WHERE user_id = :userId",
                    new MapSqlParameterSource().addValue("userId", userId).addValue("amount", amount));
            log.info("grantSubscriptionAllowance: duplicate idempotencyKey={}, skipped", idempotencyKey);
            return false;
        }

        insertCreditLot(userId, "SUBSCRIPTION_ALLOWANCE", ledgerEntryId, amount, expiresAt);
        log.info("Granted subscription allowance: user={}, amount={}, expiresAt={}", userId, amount, expiresAt);
        return true;
    }

    /**
     * Grants purchased credits. Non-expiring lot. Idempotent on idempotencyKey.
     *
     * @param userId           recipient
     * @param amount           credits purchased
     * @param transactionId    payment transaction reference
     * @param idempotencyKey   prevents double-grant on webhook retries
     * @return true if credits were granted, false if duplicate
     */
    @Transactional
    public boolean grantPurchasedCredits(UUID userId, long amount,
                                         UUID transactionId, String idempotencyKey) {
        if (amount <= 0) return true;
        ensureBalanceRowExists(userId);

        Long newBalance = jdbc.queryForObject(
                "UPDATE user_credit_balances SET balance = balance + :amount, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE user_id = :userId RETURNING balance",
                new MapSqlParameterSource().addValue("userId", userId).addValue("amount", amount),
                Long.class);
        if (newBalance == null) return false;

        UUID ledgerEntryId = insertLedgerEntry(userId, "CREDIT_PURCHASE", amount,
                newBalance, "TRANSACTION", transactionId, null, idempotencyKey);
        if (ledgerEntryId == null) {
            jdbc.update("UPDATE user_credit_balances SET balance = balance - :amount, "
                            + "updated_at = CURRENT_TIMESTAMP WHERE user_id = :userId",
                    new MapSqlParameterSource().addValue("userId", userId).addValue("amount", amount));
            log.info("grantPurchasedCredits: duplicate idempotencyKey={}, skipped", idempotencyKey);
            return false;
        }

        insertCreditLot(userId, "CREDIT_PURCHASE", ledgerEntryId, amount, null);
        log.info("Granted purchased credits: user={}, amount={}", userId, amount);
        return true;
    }

    /**
     * Grants credits awarded by a promotion redemption. Non-expiring lot. Idempotent on idempotencyKey.
     *
     * @param userId           recipient
     * @param amount           credits to grant
     * @param redemptionId     the promotion redemption UUID (for audit)
     * @param idempotencyKey   prevents double-grant on retries
     * @return true if credits were granted, false if duplicate
     */
    @Transactional
    public boolean grantPromotionCredits(UUID userId, long amount, UUID redemptionId, String idempotencyKey) {
        if (amount <= 0) return true;
        ensureBalanceRowExists(userId);

        Long newBalance = jdbc.queryForObject(
                "UPDATE user_credit_balances SET balance = balance + :amount, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE user_id = :userId RETURNING balance",
                new MapSqlParameterSource().addValue("userId", userId).addValue("amount", amount),
                Long.class);
        if (newBalance == null) return false;

        UUID ledgerEntryId = insertLedgerEntry(userId, "PROMOTION", amount,
                newBalance, "PROMOTION", redemptionId, null, idempotencyKey);
        if (ledgerEntryId == null) {
            jdbc.update("UPDATE user_credit_balances SET balance = balance - :amount, "
                            + "updated_at = CURRENT_TIMESTAMP WHERE user_id = :userId",
                    new MapSqlParameterSource().addValue("userId", userId).addValue("amount", amount));
            log.info("grantPromotionCredits: duplicate idempotencyKey={}, skipped", idempotencyKey);
            return false;
        }

        insertCreditLot(userId, "PROMOTION", ledgerEntryId, amount, null);
        log.info("Granted promotion credits: user={}, amount={}, redemption={}", userId, amount, redemptionId);
        return true;
    }

    /**
     * Deducts credits for an action. Allocates across credit lots using FIFO expiry order.
     * Throws {@link InsufficientCreditsException} if the balance is too low.
     *
     * @param userId           user performing the action
     * @param amount           credits to deduct
     * @param actionType       action code for audit (e.g. SUPER_LIKE)
     * @param idempotencyKey   prevents double-deduction on retries
     * @return the new balance after deduction
     */
    @Transactional
    public long consumeCredits(UUID userId, long amount, String actionType, String idempotencyKey) {
        if (amount <= 0) return getBalance(userId);

        ensureBalanceRowExists(userId);

        var updateParams = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("delta", -amount);
        var balanceRows = jdbc.query(UPDATE_BALANCE_SQL, updateParams,
                (rs, rn) -> rs.getLong("balance"));
        if (balanceRows.isEmpty()) {
            throw new InsufficientCreditsException(
                    "Insufficient credits: user=" + userId + " required=" + amount);
        }
        long newBalance = balanceRows.get(0);

        UUID ledgerEntryId = insertLedgerEntry(userId, "ACTION_CONSUMPTION", -amount,
                newBalance, null, null, actionType, idempotencyKey);
        if (ledgerEntryId == null) {
            // Duplicate — undo balance change
            jdbc.update("UPDATE user_credit_balances SET balance = balance + :amount, "
                            + "updated_at = CURRENT_TIMESTAMP WHERE user_id = :userId",
                    new MapSqlParameterSource().addValue("userId", userId).addValue("amount", amount));
            log.info("consumeCredits: duplicate idempotencyKey={}", idempotencyKey);
            return getBalance(userId);
        }

        allocateAcrossLots(userId, amount, ledgerEntryId);

        log.info("Consumed credits: user={}, amount={}, action={}, balance={}", userId, amount, actionType, newBalance);
        return newBalance;
    }

    /**
     * Allocates a credit deduction across existing lots using FIFO expiry order
     * (earliest-expiring first, then purchased/non-expiring).
     */
    private void allocateAcrossLots(UUID userId, long remaining, UUID ledgerEntryId) {
        List<Object[]> lots = new ArrayList<>();
        jdbc.query(FIND_LOTS_FOR_CONSUMPTION_SQL,
                new MapSqlParameterSource("userId", userId),
                (rs, rn) -> {
                    lots.add(new Object[]{rs.getObject("id", UUID.class), rs.getLong("quantity_remaining")});
                    return null;
                });

        for (Object[] lot : lots) {
            if (remaining <= 0) break;
            UUID lotId      = (UUID) lot[0];
            long lotAvail   = (Long) lot[1];
            long consume    = Math.min(lotAvail, remaining);

            int updated = jdbc.update(DECREMENT_LOT_SQL, new MapSqlParameterSource()
                    .addValue("lotId", lotId)
                    .addValue("amount", consume));
            if (updated > 0) {
                insertLotConsumption(userId, lotId, ledgerEntryId, consume);
                remaining -= consume;
            }
        }

        if (remaining > 0) {
            log.warn("allocateAcrossLots: could not fully allocate {} credits for user={}", remaining, userId);
        }
    }

    // ── Exception ───────────────────────────────────────────────────────────

    public static class InsufficientCreditsException extends RuntimeException {
        public InsufficientCreditsException(String msg) { super(msg); }
    }
}
