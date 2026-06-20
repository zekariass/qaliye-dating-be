package com.qaliye.backend.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaliye.backend.user.UserStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private static final String LOG_EVENT_SQL = """
            INSERT INTO payment_events (provider, provider_event_id, event_type, raw_payload)
            VALUES (:provider, :providerEventId, :eventType, :rawPayload::jsonb)
            ON CONFLICT (provider, provider_event_id) DO NOTHING
            """;

    private static final String FIND_PLAN_BY_CODE_SQL =
            "SELECT id, billing_interval FROM subscription_plans WHERE plan_code = :planCode AND is_active = TRUE LIMIT 1";

    private static final String FIND_PLAN_BY_ID_SQL =
            "SELECT billing_interval FROM subscription_plans WHERE id = :planId";

    private static final String UPSERT_SUBSCRIPTION_SQL = """
            INSERT INTO user_subscriptions
              (user_id, plan_id, provider, provider_subscription_id, status,
               started_at, current_period_start, current_period_end)
            VALUES
              (:userId, :planId, :provider, :providerSubscriptionId, 'ACTIVE',
               :now, :currentPeriodStart, :currentPeriodEnd)
            ON CONFLICT (provider, provider_subscription_id) DO UPDATE
              SET status = 'ACTIVE',
                  plan_id = EXCLUDED.plan_id,
                  current_period_start = EXCLUDED.current_period_start,
                  current_period_end = EXCLUDED.current_period_end,
                  updated_at = NOW()
            RETURNING id
            """;

    private static final String UPDATE_SUB_STATUS_SQL = """
            UPDATE user_subscriptions SET status = :status, updated_at = NOW()
            WHERE provider_subscription_id = :providerSubscriptionId
            """;

    private static final String UPDATE_SUB_PERIOD_SQL = """
            UPDATE user_subscriptions
            SET current_period_end = :currentPeriodEnd, updated_at = NOW()
            WHERE provider_subscription_id = :providerSubscriptionId
            """;

    private static final String INSERT_TRANSACTION_SQL = """
            INSERT INTO transactions
              (user_id, payment_purpose, amount_minor_units, currency, provider,
               status, receipt_storage_bucket, receipt_storage_path)
            VALUES
              (:userId, :paymentPurpose, :amountMinorUnits, :currency, :provider,
               'MANUAL_REVIEW', :receiptStorageBucket, :receiptStoragePath)
            RETURNING id
            """;

    private static final String FETCH_TRANSACTION_SQL = """
            SELECT t.id, t.user_id, t.status, t.payment_purpose, t.provider,
                   t.receipt_storage_bucket, t.receipt_storage_path
            FROM transactions t WHERE t.id = :transactionId FOR UPDATE
            """;

    private static final String FETCH_PLAN_FOR_TRANSACTION_SQL = """
            SELECT ti.plan_id
            FROM transaction_items ti
            WHERE ti.transaction_id = :transactionId AND ti.item_type = 'SUBSCRIPTION'
            LIMIT 1
            """;

    private static final String UPDATE_TRANSACTION_SQL = """
            UPDATE transactions SET status = :status, admin_notes = :adminNotes,
            updated_at = NOW() WHERE id = :transactionId
            """;

    private static final String LINK_TRANSACTION_SUBSCRIPTION_SQL =
            "UPDATE transaction_items SET subscription_id = :subscriptionId WHERE transaction_id = :transactionId AND item_type = 'SUBSCRIPTION'";

    private static final String INSERT_BOOST_SQL = """
            INSERT INTO active_boosts (user_id, transaction_id, expires_at)
            VALUES (:userId, :transactionId, NOW() + INTERVAL '30 minutes')
            """;

    private static final String AUDIT_SQL = """
            INSERT INTO audit_log (actor_user_id, action, target_table, target_id, details)
            VALUES (:actorId, :action, :targetTable, :targetId, :details::jsonb)
            """;

    private static final String LIST_TRANSACTIONS_SQL = """
            SELECT t.id, t.user_id, t.provider, t.amount_minor_units, t.currency,
                   t.payment_purpose, t.status, t.receipt_storage_path, t.created_at,
                   p.display_name AS user_display_name
            FROM transactions t
            LEFT JOIN profiles p ON p.user_id = t.user_id
            WHERE t.status = :status AND t.provider IN (:providers)
            ORDER BY t.created_at DESC
            LIMIT :pageSize OFFSET :offset
            """;

    private static final String COUNT_TRANSACTIONS_SQL = """
            SELECT COUNT(*) FROM transactions
            WHERE status = :status AND provider IN (:providers)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final CacheManager cacheManager;
    private final UserStatusService userStatusService;
    private final ObjectMapper objectMapper;

    public PaymentService(NamedParameterJdbcTemplate jdbc,
                          CacheManager cacheManager,
                          UserStatusService userStatusService,
                          ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.cacheManager = cacheManager;
        this.userStatusService = userStatusService;
        this.objectMapper = objectMapper;
    }

    /** Returns true if event is new (should be processed), false if duplicate. */
    @Transactional
    public boolean logAndCheck(String provider, String providerEventId,
                               String eventType, byte[] rawPayload) {
        String rawJson = new String(rawPayload, java.nio.charset.StandardCharsets.UTF_8);
        int rows = jdbc.update(LOG_EVENT_SQL, new MapSqlParameterSource()
                .addValue("provider", provider)
                .addValue("providerEventId", providerEventId)
                .addValue("eventType", eventType)
                .addValue("rawPayload", rawJson));
        return rows > 0;
    }

    // ── RevenueCat ────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    @Transactional
    public void handleRevenueCatWebhook(byte[] body) {
        Map<String, Object> payload = parse(body);
        Map<String, Object> event = (Map<String, Object>) payload.get("event");
        if (event == null) return;

        String eventType = (String) event.get("type");
        String appUserId = (String) event.get("app_user_id");
        String productId = (String) event.get("product_id");
        String transactionId = (String) event.get("transaction_id");
        String originalTransactionId = (String) event.get("original_transaction_id");
        String store = (String) event.get("store");

        UUID userId;
        try { userId = UUID.fromString(appUserId); }
        catch (Exception e) { log.warn("RevenueCat: invalid app_user_id={}", appUserId); return; }

        String provider = mapStore(store);
        String providerSubscriptionId = originalTransactionId != null
                ? originalTransactionId : transactionId;

        OffsetDateTime expirationAt = null;
        Object expMs = event.get("expiration_at_ms");
        if (expMs != null) {
            expirationAt = Instant.ofEpochMilli(((Number) expMs).longValue()).atOffset(ZoneOffset.UTC);
        }

        switch (eventType) {
            case "INITIAL_PURCHASE" -> {
                UUID planId = findPlanByCode(productId);
                if (planId == null) { log.warn("RevenueCat: unknown plan={}", productId); return; }
                OffsetDateTime end = expirationAt != null ? expirationAt : OffsetDateTime.now(ZoneOffset.UTC).plusMonths(1);
                activateSubscription(userId, planId, provider, providerSubscriptionId, OffsetDateTime.now(ZoneOffset.UTC), end);
            }
            case "RENEWAL" -> {
                if (expirationAt != null) {
                    jdbc.update(UPDATE_SUB_PERIOD_SQL, Map.of(
                            "currentPeriodEnd", Timestamp.from(expirationAt.toInstant()),
                            "providerSubscriptionId", providerSubscriptionId));
                }
            }
            case "CANCELLATION", "EXPIRATION" ->
                    jdbc.update(UPDATE_SUB_STATUS_SQL, Map.of(
                            "status", "CANCELED",
                            "providerSubscriptionId", providerSubscriptionId));
            case "BILLING_ISSUE" ->
                    jdbc.update(UPDATE_SUB_STATUS_SQL, Map.of(
                            "status", "PAST_DUE",
                            "providerSubscriptionId", providerSubscriptionId));
            case "PRODUCT_CHANGE" -> {
                UUID planId = findPlanByCode(productId);
                if (planId != null) {
                    OffsetDateTime end = expirationAt != null ? expirationAt : OffsetDateTime.now(ZoneOffset.UTC).plusMonths(1);
                    activateSubscription(userId, planId, provider, providerSubscriptionId, OffsetDateTime.now(ZoneOffset.UTC), end);
                }
            }
            default -> log.debug("RevenueCat: unhandled event type={}", eventType);
        }
    }

    // ── Stripe ────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    @Transactional
    public void handleStripeWebhook(byte[] body) {
        Map<String, Object> payload = parse(body);
        String eventType = (String) payload.get("type");
        Map<String, Object> dataObject = (Map<String, Object>)
                ((Map<String, Object>) payload.get("data")).get("object");

        switch (eventType) {
            case "checkout.session.completed" -> handleCheckoutCompleted(dataObject);
            case "invoice.paid" -> handleInvoicePaid(dataObject);
            case "invoice.payment_failed" -> handleInvoicePaymentFailed(dataObject);
            case "customer.subscription.deleted" -> handleSubscriptionDeleted(dataObject);
            default -> log.debug("Stripe: unhandled event type={}", eventType);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleCheckoutCompleted(Map<String, Object> session) {
        UUID userId = extractStripeUserId(session);
        if (userId == null) { log.warn("Stripe checkout.session.completed: no user_id"); return; }
        String providerSubscriptionId = (String) session.get("subscription");
        if (providerSubscriptionId == null) return;

        // For subscription, we need the plan — use metadata or look up from subscription
        Map<String, Object> metadata = (Map<String, Object>) session.get("metadata");
        String planCode = metadata != null ? (String) metadata.get("plan_code") : null;
        UUID planId = planCode != null ? findPlanByCode(planCode) : null;
        if (planId == null) { log.warn("Stripe: no plan for checkout session"); return; }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        activateSubscription(userId, planId, "STRIPE", providerSubscriptionId, now, now.plusMonths(1));
    }

    @SuppressWarnings("unchecked")
    private void handleInvoicePaid(Map<String, Object> invoice) {
        String providerSubscriptionId = (String) invoice.get("subscription");
        if (providerSubscriptionId == null) return;

        Object periodEnd = invoice.get("period_end");
        OffsetDateTime end = periodEnd != null
                ? Instant.ofEpochSecond(((Number) periodEnd).longValue()).atOffset(ZoneOffset.UTC)
                : OffsetDateTime.now(ZoneOffset.UTC).plusMonths(1);

        jdbc.update(UPDATE_SUB_PERIOD_SQL, Map.of(
                "currentPeriodEnd", Timestamp.from(end.toInstant()),
                "providerSubscriptionId", providerSubscriptionId));
    }

    private void handleInvoicePaymentFailed(Map<String, Object> invoice) {
        String providerSubscriptionId = (String) invoice.get("subscription");
        if (providerSubscriptionId == null) return;
        jdbc.update(UPDATE_SUB_STATUS_SQL, Map.of(
                "status", "PAST_DUE",
                "providerSubscriptionId", providerSubscriptionId));
    }

    private void handleSubscriptionDeleted(Map<String, Object> subscription) {
        String providerSubscriptionId = (String) subscription.get("id");
        if (providerSubscriptionId == null) return;
        jdbc.update(UPDATE_SUB_STATUS_SQL, Map.of(
                "status", "CANCELED",
                "providerSubscriptionId", providerSubscriptionId));
    }

    // ── Shared activation ─────────────────────────────────────────────────

    @Transactional
    public void activateSubscription(UUID userId, UUID planId, String provider,
                                     String providerSubscriptionId,
                                     OffsetDateTime currentPeriodStart,
                                     OffsetDateTime currentPeriodEnd) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<UUID> subIds = jdbc.query(UPSERT_SUBSCRIPTION_SQL, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("planId", planId)
                .addValue("provider", provider)
                .addValue("providerSubscriptionId", providerSubscriptionId)
                .addValue("now", Timestamp.from(now.toInstant()))
                .addValue("currentPeriodStart", Timestamp.from(currentPeriodStart.toInstant()))
                .addValue("currentPeriodEnd", Timestamp.from(currentPeriodEnd.toInstant())),
                (rs, rowNum) -> rs.getObject("id", UUID.class));

        evictSubscriptionCache(userId);

        String details = "{\"plan_id\": \"" + planId + "\", \"provider\": \"" + provider + "\"}";
        UUID subId = subIds.isEmpty() ? null : subIds.get(0);
        writeAuditLog(userId, "SUBSCRIPTION_ACTIVATED", "user_subscriptions",
                subId != null ? subId : userId, details);
    }

    // ── Flow B — Manual payment submission ────────────────────────────────

    @Transactional
    public UUID submitManualPayment(UUID callerId, ManualPaymentRequest req) {
        if (req.getReceiptStorageBucket() == null || req.getReceiptStoragePath() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "receipt_storage_path_required");
        }

        List<UUID> ids = jdbc.query(INSERT_TRANSACTION_SQL, new MapSqlParameterSource()
                .addValue("userId", callerId)
                .addValue("paymentPurpose", req.getPaymentPurpose())
                .addValue("amountMinorUnits", req.getAmountMinorUnits())
                .addValue("currency", req.getCurrency())
                .addValue("provider", req.getProvider())
                .addValue("receiptStorageBucket", req.getReceiptStorageBucket())
                .addValue("receiptStoragePath", req.getReceiptStoragePath()),
                (rs, rowNum) -> rs.getObject("id", UUID.class));

        return ids.get(0);
    }

    // ── Flow B — Admin review ─────────────────────────────────────────────

    @Transactional
    public Map<String, Object> reviewManualTransaction(UUID adminId, UUID transactionId,
                                                        AdminTransactionReviewRequest req) {
        requireAdminRole(adminId);

        List<Map<String, Object>> rows = jdbc.queryForList(FETCH_TRANSACTION_SQL,
                Map.of("transactionId", transactionId));
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "transaction_not_found");

        Map<String, Object> tx = rows.get(0);
        String currentStatus = (String) tx.get("status");
        if (!"MANUAL_REVIEW".equals(currentStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "transaction_already_reviewed");
        }

        jdbc.update(UPDATE_TRANSACTION_SQL, new MapSqlParameterSource()
                .addValue("status", req.getStatus())
                .addValue("adminNotes", req.getAdminNotes())
                .addValue("transactionId", transactionId));

        if ("COMPLETED".equals(req.getStatus())) {
            UUID userId = (UUID) tx.get("user_id");
            String paymentPurpose = (String) tx.get("payment_purpose");
            String provider = (String) tx.get("provider");

            if ("SUBSCRIPTION".equals(paymentPurpose)) {
                List<UUID> planIds = jdbc.query(FETCH_PLAN_FOR_TRANSACTION_SQL,
                        Map.of("transactionId", transactionId),
                        (rs, rowNum) -> rs.getObject("plan_id", UUID.class));
                if (!planIds.isEmpty()) {
                    UUID planId = planIds.get(0);
                    String billingInterval = jdbc.queryForObject(FIND_PLAN_BY_ID_SQL,
                            Map.of("planId", planId), String.class);
                    OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC);
                    OffsetDateTime end = computePeriodEnd(start, billingInterval);
                    activateSubscription(userId, planId, provider, transactionId.toString(), start, end);

                    List<UUID> subIds = jdbc.query(
                            "SELECT id FROM user_subscriptions WHERE provider_subscription_id = :sid LIMIT 1",
                            Map.of("sid", transactionId.toString()),
                            (rs, rowNum) -> rs.getObject("id", UUID.class));
                    if (!subIds.isEmpty()) {
                        jdbc.update(LINK_TRANSACTION_SUBSCRIPTION_SQL,
                                Map.of("subscriptionId", subIds.get(0), "transactionId", transactionId));
                    }
                }
            } else if ("PROFILE_BOOST".equals(paymentPurpose)) {
                jdbc.update(INSERT_BOOST_SQL, Map.of("userId", userId, "transactionId", transactionId));
            }
        }

        String details = "{\"decision\": \"" + req.getStatus() + "\""
                + (req.getAdminNotes() != null ? ", \"admin_notes\": \"" + req.getAdminNotes() + "\"" : "")
                + "}";
        writeAuditLog(adminId, "TRANSACTION_REVIEWED", "transactions", transactionId, details);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("transaction_id", transactionId);
        result.put("status", req.getStatus());
        return result;
    }

    // ── Flow B — Admin list ───────────────────────────────────────────────

    public Map<String, Object> listTransactions(UUID adminId, String status,
                                                 String providersCsv, int page, int pageSize) {
        requireAdminRole(adminId);

        List<String> providers = Arrays.asList(providersCsv.split(","));
        int offset = (Math.max(1, page) - 1) * pageSize;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("status", status)
                .addValue("providers", providers)
                .addValue("pageSize", pageSize)
                .addValue("offset", offset);

        List<TransactionItemDto> items = new ArrayList<>();
        jdbc.query(LIST_TRANSACTIONS_SQL, params, rs -> {
            items.add(new TransactionItemDto(
                    rs.getObject("id", UUID.class),
                    rs.getObject("user_id", UUID.class),
                    rs.getString("provider"),
                    rs.getInt("amount_minor_units"),
                    rs.getString("currency"),
                    rs.getString("payment_purpose"),
                    null,
                    rs.getString("receipt_storage_path"),
                    rs.getString("status"),
                    toOffsetDateTime(rs.getTimestamp("created_at")),
                    rs.getString("user_display_name")
            ));
        });

        Long total = jdbc.queryForObject(COUNT_TRANSACTIONS_SQL,
                new MapSqlParameterSource()
                        .addValue("status", status)
                        .addValue("providers", providers),
                Long.class);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", total != null ? total : 0);
        result.put("page", page);
        result.put("pageSize", pageSize);
        return result;
    }

    // ── Subscription reconciliation ───────────────────────────────────────

    @Transactional
    public void reconcileSubscriptions() {
        List<Map<String, Object>> expired = jdbc.queryForList("""
                SELECT id, user_id FROM user_subscriptions
                WHERE status = 'ACTIVE'
                  AND current_period_end < NOW() - INTERVAL '3 days'
                """, Map.of());

        for (Map<String, Object> row : expired) {
            UUID id = (UUID) row.get("id");
            UUID userId = (UUID) row.get("user_id");
            jdbc.update("UPDATE user_subscriptions SET status = 'CANCELED' WHERE id = :id",
                    Map.of("id", id));
            evictSubscriptionCache(userId);
        }

        List<Map<String, Object>> gracePeriod = jdbc.queryForList("""
                SELECT id, user_id FROM user_subscriptions
                WHERE status = 'ACTIVE'
                  AND current_period_end < NOW()
                  AND current_period_end >= NOW() - INTERVAL '3 days'
                """, Map.of());

        for (Map<String, Object> row : gracePeriod) {
            UUID id = (UUID) row.get("id");
            UUID userId = (UUID) row.get("user_id");
            jdbc.update("UPDATE user_subscriptions SET status = 'PAST_DUE' WHERE id = :id",
                    Map.of("id", id));
            evictSubscriptionCache(userId);
        }

        log.info("Subscription reconciliation: {} canceled, {} past_due",
                expired.size(), gracePeriod.size());
    }

    private void evictSubscriptionCache(UUID userId) {
        Cache cache = cacheManager.getCache("subscriptionFeatures");
        if (cache != null) cache.evict(userId);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private UUID findPlanByCode(String planCode) {
        List<UUID> ids = jdbc.query(
                "SELECT id FROM subscription_plans WHERE plan_code = :planCode AND is_active = TRUE LIMIT 1",
                Map.of("planCode", planCode),
                (rs, rowNum) -> rs.getObject("id", UUID.class));
        return ids.isEmpty() ? null : ids.get(0);
    }

    private OffsetDateTime computePeriodEnd(OffsetDateTime start, String billingInterval) {
        if (billingInterval == null) return start.plusMonths(1);
        return switch (billingInterval) {
            case "WEEKLY" -> start.plusWeeks(1);
            case "YEARLY" -> start.plusYears(1);
            default -> start.plusMonths(1);
        };
    }

    private String mapStore(String store) {
        if (store == null) return "STRIPE";
        return switch (store.toLowerCase()) {
            case "app_store" -> "APPLE_APP_STORE";
            case "play_store" -> "GOOGLE_PLAY";
            default -> "STRIPE";
        };
    }

    @SuppressWarnings("unchecked")
    private UUID extractStripeUserId(Map<String, Object> dataObject) {
        String clientRef = (String) dataObject.get("client_reference_id");
        if (clientRef != null) {
            try { return UUID.fromString(clientRef); } catch (Exception ignored) {}
        }
        Map<String, Object> metadata = (Map<String, Object>) dataObject.get("metadata");
        if (metadata != null) {
            String userId = (String) metadata.get("user_id");
            if (userId != null) {
                try { return UUID.fromString(userId); } catch (Exception ignored) {}
            }
        }
        String subId = (String) dataObject.get("subscription");
        if (subId != null) {
            List<UUID> userIds = jdbc.query(
                    "SELECT user_id FROM user_subscriptions WHERE provider_subscription_id = :subId LIMIT 1",
                    Map.of("subId", subId),
                    (rs, rowNum) -> rs.getObject("user_id", UUID.class));
            if (!userIds.isEmpty()) return userIds.get(0);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(byte[] body) {
        try { return objectMapper.readValue(body, Map.class); }
        catch (Exception e) { throw new RuntimeException("Invalid JSON payload", e); }
    }

    private void requireAdminRole(UUID callerId) {
        UserStatusService.UserStatus status = userStatusService.getStatus(callerId);
        if (status == null || !"ADMIN".equals(status.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "access_denied");
        }
    }

    private void writeAuditLog(UUID actorId, String action, String targetTable,
                               UUID targetId, String details) {
        jdbc.update(AUDIT_SQL, new MapSqlParameterSource()
                .addValue("actorId", actorId)
                .addValue("action", action)
                .addValue("targetTable", targetTable)
                .addValue("targetId", targetId)
                .addValue("details", details));
    }

    private OffsetDateTime toOffsetDateTime(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }
}
