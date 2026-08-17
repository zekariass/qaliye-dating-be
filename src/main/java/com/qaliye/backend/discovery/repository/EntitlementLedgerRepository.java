package com.qaliye.backend.discovery.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class EntitlementLedgerRepository {

    private static final Logger log = LoggerFactory.getLogger(EntitlementLedgerRepository.class);

    private final NamedParameterJdbcTemplate jdbc;

    public EntitlementLedgerRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String GET_BALANCE_FROM_LOTS = """
            SELECT COALESCE(SUM(quantity_remaining), 0)
            FROM user_entitlement_credit_lots
            WHERE user_id = :userId
              AND entitlement_type = :type
              AND quantity_remaining > 0
              AND (expires_at IS NULL OR expires_at > NOW())
            """;

    private static final String GET_BALANCE_LEDGER_FALLBACK = """
            SELECT COALESCE(SUM(quantity_delta), 0)
            FROM user_entitlement_ledger
            WHERE user_id = :userId
              AND entitlement_type = :type
              AND (expires_at IS NULL OR expires_at > NOW())
            """;

    private static final String CONSUME_CREDIT = """
            INSERT INTO user_entitlement_ledger
                (user_id, entitlement_type, quantity_delta, reason, related_discovery_action_id, idempotency_key)
            VALUES
                (:userId, :type, -1, 'CONSUMPTION', :relatedActionId, :idempotencyKey)
            RETURNING id
            """;

    private static final String FIND_OLDEST_LOT = """
            SELECT id, quantity_remaining
            FROM user_entitlement_credit_lots
            WHERE user_id = :userId
              AND entitlement_type = :type
              AND quantity_remaining > 0
              AND (expires_at IS NULL OR expires_at > NOW())
            ORDER BY created_at ASC
            LIMIT 1
            FOR UPDATE
            """;

    private static final String DECREMENT_LOT = """
            UPDATE user_entitlement_credit_lots
            SET quantity_remaining = quantity_remaining - 1
            WHERE id = :lotId AND quantity_remaining > 0
            """;

    // user_entitlement_credit_consumptions was dropped in V53;
    // legacy consumption records are no longer written here.
    private static final String INSERT_CONSUMPTION_RECORD = null;

    public int getBalance(UUID userId, String entitlementType) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("type", entitlementType);
        // Try lot-based balance first
        Integer lotBalance = jdbc.queryForObject(GET_BALANCE_FROM_LOTS, params, Integer.class);
        if (lotBalance != null && lotBalance > 0) return lotBalance;
        // Fallback to ledger sum for backwards compatibility
        Integer result = jdbc.queryForObject(GET_BALANCE_LEDGER_FALLBACK, params, Integer.class);
        return result != null ? Math.max(result, 0) : 0;
    }

    public void consumeCredit(UUID userId, String entitlementType, UUID relatedActionId, UUID idempotencyKey) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("type", entitlementType)
                .addValue("relatedActionId", relatedActionId)
                .addValue("idempotencyKey", idempotencyKey != null ? idempotencyKey.toString() : UUID.randomUUID().toString());

        // Insert ledger entry
        List<UUID> ledgerIds = jdbc.query(CONSUME_CREDIT, params,
                (rs, rowNum) -> rs.getObject("id", UUID.class));
        UUID ledgerEntryId = ledgerIds.isEmpty() ? null : ledgerIds.get(0);

        // Decrement oldest lot (FIFO)
        var lotParams = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("type", entitlementType);
        List<UUID> lotIds = jdbc.query(FIND_OLDEST_LOT, lotParams,
                (rs, rowNum) -> rs.getObject("id", UUID.class));

        if (!lotIds.isEmpty() && ledgerEntryId != null) {
            UUID lotId = lotIds.get(0);
            jdbc.update(DECREMENT_LOT, new MapSqlParameterSource("lotId", lotId));
        }
    }
}
