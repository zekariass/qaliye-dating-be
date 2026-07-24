package com.qaliye.backend.admin;

import com.qaliye.backend.billing.repository.BillingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AdminRefundService {

    private static final Logger log = LoggerFactory.getLogger(AdminRefundService.class);

    private static final String FIND_TX_BY_ORDER_SQL = """
            SELECT id, payment_purpose, amount_minor_units, currency, provider, country_code
            FROM transactions
            WHERE payment_order_id = :orderId AND status = 'COMPLETED'
            ORDER BY created_at DESC
            LIMIT 1
            """;

    private static final String FIND_SUB_BY_ORDER_SQL = """
            SELECT id FROM user_subscriptions
            WHERE provider_subscription_id = :orderReference
               OR provider_subscription_id = :orderIdStr
            ORDER BY created_at DESC
            LIMIT 1
            """;

    private static final String CANCEL_SUB_BY_ID_SQL = """
            UPDATE user_subscriptions
            SET status = 'CANCELED', cancelled_at = NOW(), updated_at = NOW()
            WHERE id = :subId AND status IN ('ACTIVE', 'PAST_DUE', 'UNPAID', 'PENDING_VERIFICATION')
            """;

    private static final String EXPIRE_LOTS_BY_TX_SQL = """
            UPDATE credit_lots
            SET status = 'EXPIRED', updated_at = NOW()
            WHERE ledger_entry_id IN (
                SELECT id FROM credit_ledger_entries
                WHERE transaction_id = :transactionId
            )
            AND status = 'ACTIVE'
            """;

    private static final String CANCEL_BOOSTS_BY_TX_SQL = """
            UPDATE profile_boosts
            SET status = 'CANCELLED', updated_at = NOW()
            WHERE ledger_entry_id IN (
                SELECT id FROM credit_ledger_entries
                WHERE transaction_id = :transactionId
            )
            AND status = 'ACTIVE'
            """;

    private static final String MARK_TX_REFUNDED_SQL = """
            UPDATE transactions SET status = 'REFUNDED', updated_at = NOW()
            WHERE id = :transactionId AND status = 'COMPLETED'
            """;

    private static final String AUDIT_LOG_SQL = """
            INSERT INTO audit_log (actor_user_id, action, target_table, target_id, details)
            VALUES (:actorId, :action, :targetTable, :targetId, :details::jsonb)
            """;

    private static final String CHECK_ADMIN_SQL = """
            SELECT role FROM app_users WHERE id = :userId
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final BillingRepository billingRepo;
    private final CacheManager cacheManager;

    public AdminRefundService(NamedParameterJdbcTemplate jdbc,
                              BillingRepository billingRepo,
                              CacheManager cacheManager) {
        this.jdbc = jdbc;
        this.billingRepo = billingRepo;
        this.cacheManager = cacheManager;
    }

    @Transactional
    public Map<String, Object> refundOrder(UUID adminId, UUID orderId, String reason) {
        requireAdmin(adminId);

        BillingRepository.OrderRow order = billingRepo.findOrderById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "order_not_found"));

        if (!"VERIFIED".equals(order.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "only_verified_orders_can_be_refunded");
        }

        // Find the completed transaction for this order
        List<Map<String, Object>> txRows = jdbc.queryForList(FIND_TX_BY_ORDER_SQL,
                Map.of("orderId", orderId));
        if (txRows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no_completed_transaction_for_order");
        }

        Map<String, Object> tx = txRows.get(0);
        UUID transactionId = (UUID) tx.get("id");
        String paymentPurpose = (String) tx.get("payment_purpose");
        int amount = ((Number) tx.get("amount_minor_units")).intValue();
        String currency = (String) tx.get("currency");
        String provider = (String) tx.get("provider");
        String countryCode = (String) tx.get("country_code");

        // 1. Mark the original transaction as REFUNDED
        jdbc.update(MARK_TX_REFUNDED_SQL, Map.of("transactionId", transactionId));

        // 2. Insert a refund transaction record
        billingRepo.insertTransaction(
                order.userId(), null, orderId, order.paymentOfferId(), transactionId,
                paymentPurpose, "REFUND",
                amount, currency, provider,
                "REFUND-" + order.orderReference(), null, countryCode, "REFUNDED"
        );

        // 3. Cancel subscription if this was a subscription order
        cancelSubscriptionForOrder(order);

        // 4. Expire credit lots and cancel boosts from this transaction
        jdbc.update(EXPIRE_LOTS_BY_TX_SQL, Map.of("transactionId", transactionId));
        jdbc.update(CANCEL_BOOSTS_BY_TX_SQL, Map.of("transactionId", transactionId));

        // 5. Mark order as CANCELLED
        billingRepo.updateOrderStatus(orderId, "CANCELLED",
                "admin refund: " + (reason != null ? reason : "no reason provided"));

        // 6. Evict subscription cache
        evictSubscriptionCache(order.userId());

        // 7. Audit log
        String details = "{\"reason\": \"" + escapeJson(reason != null ? reason : "admin_initiated") + "\""
                + ", \"previousStatus\": \"VERIFIED\""
                + ", \"transactionId\": \"" + transactionId + "\""
                + "}";
        writeAuditLog(adminId, "ADMIN_REFUND_ORDER", "payment_orders", orderId, details);

        log.info("Admin {} refunded order {} (amount={} {})", adminId, orderId, amount, currency);

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("orderId", orderId);
        result.put("status", "CANCELLED");
        result.put("transactionId", transactionId);
        result.put("refundAmount", amount);
        result.put("currency", currency);
        return result;
    }

    private void cancelSubscriptionForOrder(BillingRepository.OrderRow order) {
        // Try to find subscription by order reference or order ID
        List<UUID> subIds = jdbc.query(FIND_SUB_BY_ORDER_SQL,
                new MapSqlParameterSource()
                        .addValue("orderReference", order.orderReference())
                        .addValue("orderIdStr", order.id().toString()),
                (rs, n) -> rs.getObject("id", UUID.class));
        if (!subIds.isEmpty()) {
            int cancelled = jdbc.update(CANCEL_SUB_BY_ID_SQL, Map.of("subId", subIds.get(0)));
            if (cancelled > 0) {
                log.info("Cancelled subscription {} for refunded order {}", subIds.get(0), order.id());
            }
        }
    }

    private void evictSubscriptionCache(UUID userId) {
        try {
            var cache = cacheManager.getCache("subscriptionFeatures");
            if (cache != null) {
                cache.evict(userId);
            }
        } catch (Exception e) {
            log.warn("Failed to evict subscriptionFeatures cache for {}: {}", userId, e.getMessage());
        }
    }

    private void requireAdmin(UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(CHECK_ADMIN_SQL,
                Map.of("userId", userId));
        if (rows.isEmpty() || !"ADMIN".equals(rows.get(0).get("role"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin_required");
        }
    }

    private void writeAuditLog(UUID actorId, String action, String targetTable,
                               UUID targetId, String details) {
        jdbc.update(AUDIT_LOG_SQL, new MapSqlParameterSource()
                .addValue("actorId", actorId)
                .addValue("action", action)
                .addValue("targetTable", targetTable)
                .addValue("targetId", targetId)
                .addValue("details", details));
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
