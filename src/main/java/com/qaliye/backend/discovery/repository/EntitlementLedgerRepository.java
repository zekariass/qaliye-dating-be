package com.qaliye.backend.discovery.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class EntitlementLedgerRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public EntitlementLedgerRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String GET_BALANCE = """
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
            """;

    public int getBalance(UUID userId, String entitlementType) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("type", entitlementType);
        Integer result = jdbc.queryForObject(GET_BALANCE, params, Integer.class);
        return result != null ? result : 0;
    }

    public void consumeCredit(UUID userId, String entitlementType, UUID relatedActionId, UUID idempotencyKey) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("type", entitlementType)
                .addValue("relatedActionId", relatedActionId)
                .addValue("idempotencyKey", idempotencyKey);
        jdbc.update(CONSUME_CREDIT, params);
    }
}
