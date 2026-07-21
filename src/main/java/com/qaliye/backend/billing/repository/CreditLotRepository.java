package com.qaliye.backend.billing.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class CreditLotRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public CreditLotRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Credit balance (from lots, not ledger sum) ──────────────────────────

    private static final String GET_BALANCE_SQL = """
            SELECT COALESCE(SUM(quantity_remaining), 0)
            FROM user_entitlement_credit_lots
            WHERE user_id = :userId
              AND entitlement_type = :type
              AND quantity_remaining > 0
              AND (expires_at IS NULL OR expires_at > NOW())
            """;

    public int getBalance(UUID userId, String entitlementType) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("type", entitlementType);
        Integer result = jdbc.queryForObject(GET_BALANCE_SQL, params, Integer.class);
        return result != null ? result : 0;
    }

    // ── Find oldest valid lot with remaining credits (locked) ───────────────

    private static final String FIND_OLDEST_LOT_SQL = """
            SELECT id, quantity_remaining
            FROM user_entitlement_credit_lots
            WHERE user_id = :userId
              AND entitlement_type = :type
              AND quantity_remaining > 0
              AND (expires_at IS NULL OR expires_at > NOW())
            ORDER BY expires_at ASC NULLS LAST, created_at ASC
            LIMIT 1
            FOR UPDATE
            """;

    public record LotRow(UUID id, int quantityRemaining) {}

    public List<LotRow> findOldestValidLot(UUID userId, String entitlementType) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("type", entitlementType);
        return jdbc.query(FIND_OLDEST_LOT_SQL, params, (rs, rowNum) ->
                new LotRow(rs.getObject("id", UUID.class), rs.getInt("quantity_remaining")));
    }

    // ── Decrement lot ───────────────────────────────────────────────────────

    private static final String DECREMENT_LOT_SQL = """
            UPDATE user_entitlement_credit_lots
            SET quantity_remaining = quantity_remaining - :quantity
            WHERE id = :lotId AND quantity_remaining >= :quantity
            """;

    public int decrementLot(UUID lotId, int quantity) {
        return jdbc.update(DECREMENT_LOT_SQL, Map.of("lotId", lotId, "quantity", quantity));
    }

    // ── Create lot ──────────────────────────────────────────────────────────

    private static final String INSERT_LOT_SQL = """
            INSERT INTO user_entitlement_credit_lots
                (user_id, entitlement_type, source_ledger_entry_id,
                 quantity_granted, quantity_remaining, expires_at)
            VALUES
                (:userId, :type, :ledgerEntryId, :granted, :granted, :expiresAt)
            RETURNING id
            """;

    public UUID createLot(UUID userId, String entitlementType, UUID ledgerEntryId,
                          int quantityGranted, Instant expiresAt) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("type", entitlementType)
                .addValue("ledgerEntryId", ledgerEntryId)
                .addValue("granted", quantityGranted)
                .addValue("expiresAt", expiresAt != null ? java.sql.Timestamp.from(expiresAt) : null);
        return jdbc.queryForObject(INSERT_LOT_SQL, params, (rs, rowNum) -> rs.getObject("id", UUID.class));
    }

    // ── Ledger entry ────────────────────────────────────────────────────────

    private static final String INSERT_LEDGER_SQL = """
            INSERT INTO user_entitlement_ledger
                (user_id, entitlement_type, quantity_delta, reason,
                 transaction_id, subscription_id, related_discovery_action_id,
                 idempotency_key, expires_at, metadata)
            VALUES
                (:userId, :type, :delta, :reason,
                 :transactionId, :subscriptionId, :relatedActionId,
                 :idempotencyKey, :expiresAt, :metadata::jsonb)
            ON CONFLICT (user_id, idempotency_key) WHERE idempotency_key IS NOT NULL DO NOTHING
            RETURNING id
            """;

    public UUID insertLedgerEntry(UUID userId, String entitlementType, int quantityDelta,
                                  String reason, UUID transactionId, UUID subscriptionId,
                                  UUID relatedActionId, String idempotencyKey,
                                  Instant expiresAt, String metadata) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("type", entitlementType)
                .addValue("delta", quantityDelta)
                .addValue("reason", reason)
                .addValue("transactionId", transactionId)
                .addValue("subscriptionId", subscriptionId)
                .addValue("relatedActionId", relatedActionId)
                .addValue("idempotencyKey", idempotencyKey)
                .addValue("expiresAt", expiresAt != null ? java.sql.Timestamp.from(expiresAt) : null)
                .addValue("metadata", metadata != null ? metadata : "{}");
        var results = jdbc.query(INSERT_LEDGER_SQL, params,
                (rs, rowNum) -> rs.getObject("id", UUID.class));
        return results.isEmpty() ? null : results.get(0);
    }

    // ── Credit consumption record ───────────────────────────────────────────

    private static final String INSERT_CONSUMPTION_SQL = """
            INSERT INTO user_entitlement_credit_consumptions
                (consumption_ledger_entry_id, credit_lot_id, quantity_consumed)
            VALUES
                (:ledgerEntryId, :lotId, :quantity)
            """;

    public void insertConsumption(UUID ledgerEntryId, UUID lotId, int quantity) {
        jdbc.update(INSERT_CONSUMPTION_SQL, Map.of(
                "ledgerEntryId", ledgerEntryId,
                "lotId", lotId,
                "quantity", quantity));
    }

    // ── Expire credit lots past their expiry date ───────────────────────────

    private static final String FIND_EXPIRED_LOTS_SQL = """
            SELECT id, user_id, entitlement_type, quantity_remaining
            FROM user_entitlement_credit_lots
            WHERE expires_at IS NOT NULL
              AND expires_at <= NOW()
              AND quantity_remaining > 0
            FOR UPDATE
            """;

    private static final String ZERO_OUT_LOT_SQL = """
            UPDATE user_entitlement_credit_lots
            SET quantity_remaining = 0
            WHERE id = :lotId
            """;

    public record ExpiredLotRow(UUID id, UUID userId, String entitlementType, int quantityRemaining) {}

    public List<ExpiredLotRow> findExpiredLots() {
        return jdbc.query(FIND_EXPIRED_LOTS_SQL, Map.of(), (rs, rowNum) ->
                new ExpiredLotRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("entitlement_type"),
                        rs.getInt("quantity_remaining")));
    }

    public void zeroOutLot(UUID lotId) {
        jdbc.update(ZERO_OUT_LOT_SQL, Map.of("lotId", lotId));
    }

    /**
     * Expires all credit lots past their expiry date: sets quantity_remaining to 0
     * and inserts EXPIRY ledger entries for audit.
     * @return number of lots expired
     */
    public int expireCreditLots() {
        List<ExpiredLotRow> expired = findExpiredLots();
        for (ExpiredLotRow lot : expired) {
            zeroOutLot(lot.id());
            String idemKey = "expiry-" + lot.id();
            insertLedgerEntry(
                    lot.userId(), lot.entitlementType(), -lot.quantityRemaining(),
                    "EXPIRY", null, null, null, idemKey, null,
                    "{\"reason\":\"lot_expired\",\"lotId\":\"" + lot.id() + "\"}"
            );
        }
        return expired.size();
    }

    // ── Active boost check ──────────────────────────────────────────────────

    private static final String FIND_ACTIVE_BOOST_SQL = """
            SELECT id, started_at, expires_at
            FROM active_boosts
            WHERE user_id = :userId
              AND status = 'ACTIVE'
              AND expires_at > NOW()
            LIMIT 1
            """;

    public record ActiveBoostRow(UUID id, Instant startedAt, Instant expiresAt) {}

    public List<ActiveBoostRow> findActiveBoost(UUID userId) {
        return jdbc.query(FIND_ACTIVE_BOOST_SQL, Map.of("userId", userId), (rs, rowNum) ->
                new ActiveBoostRow(
                        rs.getObject("id", UUID.class),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant()
                ));
    }

    // ── Insert boost ────────────────────────────────────────────────────────

    private static final String INSERT_BOOST_SQL = """
            INSERT INTO active_boosts
                (user_id, consumption_ledger_entry_id, status, started_at, expires_at)
            VALUES
                (:userId, :ledgerEntryId, 'ACTIVE', NOW(), NOW() + make_interval(mins => :durationMinutes))
            RETURNING id, started_at, expires_at
            """;

    public record BoostInsertRow(UUID id, Instant startedAt, Instant expiresAt) {}

    // ── Plan limit lookup ───────────────────────────────────────────────────

    private static final String GET_PLAN_BOOST_LIMIT_SQL = """
            SELECT limit_value
            FROM subscription_plan_limits
            WHERE plan_id = :planId
              AND limit_type = 'BOOSTS'
            """;

    /**
     * Returns the BOOSTS limit_value for the given plan, or 1 if not found.
     * A NULL limit_value means unlimited; we cap at a reasonable default (1) since
     * unlimited boosts don't make sense as a monthly allowance.
     */
    public int getPlanBoostLimit(UUID planId) {
        try {
            Integer val = jdbc.queryForObject(
                    GET_PLAN_BOOST_LIMIT_SQL,
                    new MapSqlParameterSource("planId", planId),
                    Integer.class);
            return val != null ? val : 1;
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return 1;
        }
    }

    public BoostInsertRow insertBoost(UUID userId, UUID ledgerEntryId, int durationMinutes) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("ledgerEntryId", ledgerEntryId)
                .addValue("durationMinutes", durationMinutes);
        return jdbc.queryForObject(INSERT_BOOST_SQL, params, (rs, rowNum) -> new BoostInsertRow(
                rs.getObject("id", UUID.class),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant()
        ));
    }

    // ── Account deletion: expire all credits and cancel active boosts ───────

    private static final String EXPIRE_ALL_LOTS_FOR_USER_SQL = """
            UPDATE user_entitlement_credit_lots
            SET quantity_remaining = 0
            WHERE user_id = :userId
              AND quantity_remaining > 0
            """;

    public int expireAllCreditLotsForUser(UUID userId) {
        return jdbc.update(EXPIRE_ALL_LOTS_FOR_USER_SQL, Map.of("userId", userId));
    }

    private static final String CANCEL_ACTIVE_BOOSTS_FOR_USER_SQL = """
            UPDATE active_boosts
            SET status   = 'CANCELLED',
                ended_at  = NOW()
            WHERE user_id = :userId
              AND status = 'ACTIVE'
            """;

    public int cancelActiveBoostsForUser(UUID userId) {
        return jdbc.update(CANCEL_ACTIVE_BOOSTS_FOR_USER_SQL, Map.of("userId", userId));
    }
}
