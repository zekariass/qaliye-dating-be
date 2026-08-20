package com.qaliye.backend.billing.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class BillingRepository {

    private static final Logger log = LoggerFactory.getLogger(BillingRepository.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public BillingRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // ── Offers ──────────────────────────────────────────────────────────────

    private static final String FIND_OFFERS_SQL = """
            SELECT po.id, po.subscription_product_id, po.consumable_product_id, po.country_code, po.platform,
                   po.currency, po.price_minor_units, po.auto_renew,
                   po.external_product_id, po.revenuecat_offering_id, po.revenuecat_package_id,
                   sp.product_code AS sub_product_code,
                   sp.billing_interval_unit, sp.billing_interval_count,
                   COALESCE(sp.included_credits, 0) AS included_credits,
                   cp.product_code AS con_product_code,
                   cp.entitlement_type, cp.quantity_granted
            FROM payment_offers po
            LEFT JOIN subscription_products sp ON sp.id = po.subscription_product_id
            LEFT JOIN consumable_products cp ON cp.id = po.consumable_product_id
            WHERE po.is_active = TRUE
              AND po.platform = :platform
              AND po.country_code = :countryCode
            ORDER BY po.price_minor_units ASC
            """;

    public record OfferRow(
            UUID id, UUID subscriptionProductId, UUID consumableProductId, String countryCode, String platform,
            String currency, int priceMinorUnits, boolean autoRenew,
            String externalProductId, String revenuecatOfferingId, String revenuecatPackageId,
            String subProductCode, String billingIntervalUnit, Integer billingIntervalCount,
            long includedCredits,
            String conProductCode, String entitlementType, Integer quantityGranted
    ) {}

    public List<OfferRow> findActiveOffers(String platform, String countryCode) {
        var params = new MapSqlParameterSource()
                .addValue("platform", platform)
                .addValue("countryCode", countryCode);
        return jdbc.query(FIND_OFFERS_SQL, params, (rs, rowNum) -> new OfferRow(
                rs.getObject("id", UUID.class),
                rs.getObject("subscription_product_id", UUID.class),
                rs.getObject("consumable_product_id", UUID.class),
                rs.getString("country_code"),
                rs.getString("platform"),
                rs.getString("currency"),
                rs.getInt("price_minor_units"),
                rs.getBoolean("auto_renew"),
                rs.getString("external_product_id"),
                rs.getString("revenuecat_offering_id"),
                rs.getString("revenuecat_package_id"),
                rs.getString("sub_product_code"),
                rs.getString("billing_interval_unit"),
                rs.getObject("billing_interval_count") != null ? rs.getInt("billing_interval_count") : null,
                rs.getLong("included_credits"),
                rs.getString("con_product_code"),
                rs.getString("entitlement_type"),
                rs.getObject("quantity_granted") != null ? rs.getInt("quantity_granted") : null
        ));
    }

    // ── Payment offer by ID ─────────────────────────────────────────────────

    private static final String FIND_OFFER_BY_ID_SQL = """
            SELECT po.id, po.subscription_product_id, po.consumable_product_id,
                   po.country_code, po.platform,
                   po.currency, po.price_minor_units, po.external_product_id,
                   po.revenuecat_offering_id, po.revenuecat_package_id, po.auto_renew,
                   sp.product_code AS sub_product_code,
                   sp.billing_interval_unit, sp.billing_interval_count, sp.plan_id,
                   COALESCE(sp.included_credits, 0) AS included_credits,
                   cp.product_code AS con_product_code,
                   cp.entitlement_type, cp.quantity_granted, cp.expires_after_days
            FROM payment_offers po
            LEFT JOIN subscription_products sp ON sp.id = po.subscription_product_id
            LEFT JOIN consumable_products cp ON cp.id = po.consumable_product_id
            WHERE po.id = :offerId AND po.is_active = TRUE
            """;

    public record FullOfferRow(
            UUID id, UUID subscriptionProductId, UUID consumableProductId,
            String countryCode, String platform,
            String currency, int priceMinorUnits, boolean autoRenew,
            String externalProductId,
            String subProductCode, String billingIntervalUnit, Integer billingIntervalCount, UUID planId,
            long includedCredits,
            String conProductCode, String entitlementType, Integer quantityGranted, Integer expiresAfterDays
    ) {}

    public Optional<FullOfferRow> findOfferById(UUID offerId) {
        var params = new MapSqlParameterSource("offerId", offerId);
        return jdbc.query(FIND_OFFER_BY_ID_SQL, params, (rs, rowNum) -> new FullOfferRow(
                rs.getObject("id", UUID.class),
                rs.getObject("subscription_product_id", UUID.class),
                rs.getObject("consumable_product_id", UUID.class),
                rs.getString("country_code"),
                rs.getString("platform"),
                rs.getString("currency"),
                rs.getInt("price_minor_units"),
                rs.getBoolean("auto_renew"),
                rs.getString("external_product_id"),
                rs.getString("sub_product_code"),
                rs.getString("billing_interval_unit"),
                rs.getObject("billing_interval_count") != null ? rs.getInt("billing_interval_count") : null,
                rs.getObject("plan_id", UUID.class),
                rs.getLong("included_credits"),
                rs.getString("con_product_code"),
                rs.getString("entitlement_type"),
                rs.getObject("quantity_granted") != null ? rs.getInt("quantity_granted") : null,
                rs.getObject("expires_after_days") != null ? rs.getInt("expires_after_days") : null
        )).stream().findFirst();
    }

    // ── Offer by external product ID (RevenueCat) ───────────────────────────

    private static final String FIND_OFFER_BY_EXTERNAL_ID_SQL = """
            SELECT po.id, po.subscription_product_id, po.consumable_product_id,
                   po.country_code, po.platform,
                   po.currency, po.price_minor_units, po.external_product_id,
                   po.revenuecat_offering_id, po.revenuecat_package_id, po.auto_renew,
                   sp.product_code AS sub_product_code,
                   sp.billing_interval_unit, sp.billing_interval_count, sp.plan_id,
                   COALESCE(sp.included_credits, 0) AS included_credits,
                   cp.product_code AS con_product_code,
                   cp.entitlement_type, cp.quantity_granted, cp.expires_after_days
            FROM payment_offers po
            LEFT JOIN subscription_products sp ON sp.id = po.subscription_product_id
            LEFT JOIN consumable_products cp ON cp.id = po.consumable_product_id
            WHERE po.external_product_id = :externalProductId
              AND po.revenuecat_offering_id IS NOT NULL
              AND po.is_active = TRUE
            LIMIT 1
            """;

    public Optional<FullOfferRow> findOfferByExternalProductId(String externalProductId) {
        var params = new MapSqlParameterSource("externalProductId", externalProductId);
        return jdbc.query(FIND_OFFER_BY_EXTERNAL_ID_SQL, params, (rs, rowNum) -> new FullOfferRow(
                rs.getObject("id", UUID.class),
                rs.getObject("subscription_product_id", UUID.class),
                rs.getObject("consumable_product_id", UUID.class),
                rs.getString("country_code"),
                rs.getString("platform"),
                rs.getString("currency"),
                rs.getInt("price_minor_units"),
                rs.getBoolean("auto_renew"),
                rs.getString("external_product_id"),
                rs.getString("sub_product_code"),
                rs.getString("billing_interval_unit"),
                rs.getObject("billing_interval_count") != null ? rs.getInt("billing_interval_count") : null,
                rs.getObject("plan_id", UUID.class),
                rs.getLong("included_credits"),
                rs.getString("con_product_code"),
                rs.getString("entitlement_type"),
                rs.getObject("quantity_granted") != null ? rs.getInt("quantity_granted") : null,
                rs.getObject("expires_after_days") != null ? rs.getInt("expires_after_days") : null
        )).stream().findFirst();
    }

    // ── Payment methods ─────────────────────────────────────────────────────

    private static final String FIND_PAYMENT_METHODS_SQL = """
            SELECT id, country_code, platform, method_code, display_name,
                   payment_channel, payment_method, payment_instructions, is_active, display_order,
                   verification_params, logo_url
            FROM payment_methods
            WHERE country_code = :countryCode AND platform = :platform AND is_active = TRUE
            ORDER BY display_order ASC
            """;

    private static final String FIND_PAYMENT_METHOD_BY_ID_SQL = """
            SELECT id, country_code, platform, method_code, display_name,
                   payment_channel, payment_method, payment_instructions, is_active, display_order,
                   verification_params, logo_url
            FROM payment_methods
            WHERE id = :id
            """;

    private static final String FIND_DISTINCT_PAYMENT_CHANNELS_SQL = """
            SELECT DISTINCT payment_channel
            FROM payment_methods
            WHERE country_code = :countryCode AND platform = :platform AND is_active = TRUE
            ORDER BY payment_channel ASC
            """;

    private static final String FIND_PAYMENT_METHODS_BY_CHANNEL_SQL = """
            SELECT id, country_code, platform, method_code, display_name,
                   payment_channel, payment_method, payment_instructions, is_active, display_order,
                   verification_params, logo_url
            FROM payment_methods
            WHERE country_code = :countryCode AND platform = :platform
              AND payment_channel = :paymentChannel AND is_active = TRUE
            ORDER BY display_order ASC
            """;

    private static final String COUNT_ACTIVE_PAYMENT_METHODS_SQL = """
            SELECT COUNT(*) FROM payment_methods
            WHERE country_code = :countryCode AND platform = :platform AND is_active = TRUE
            """;

    private static final String FIND_ACTIVE_ONLINE_PAYMENT_METHOD_SQL = """
            SELECT id, country_code, platform, method_code, display_name,
                   payment_channel, payment_method, payment_instructions, is_active, display_order,
                   verification_params, logo_url
            FROM payment_methods
            WHERE country_code = :countryCode AND platform = :platform
              AND payment_channel = 'ONLINE_PAYMENT' AND is_active = TRUE
            ORDER BY display_order ASC
            LIMIT 1
            """;

    public Optional<PaymentMethodRow> findActiveOnlinePaymentMethod(String countryCode, String platform) {
        var params = new MapSqlParameterSource()
                .addValue("countryCode", countryCode)
                .addValue("platform", platform);
        return jdbc.query(FIND_ACTIVE_ONLINE_PAYMENT_METHOD_SQL, params, this::mapPaymentMethodRow)
                .stream().findFirst();
    }

    private static final String COUNT_ACTIVE_PAYMENT_METHODS_BY_CHANNEL_SQL = """
            SELECT COUNT(*) FROM payment_methods
            WHERE country_code = :countryCode AND platform = :platform
              AND payment_channel = :paymentChannel AND is_active = TRUE
            """;

    public int countActivePaymentMethodsByChannel(String countryCode, String platform, String channel) {
        var params = new MapSqlParameterSource()
                .addValue("countryCode", countryCode)
                .addValue("platform", platform)
                .addValue("paymentChannel", channel);
        Long count = jdbc.queryForObject(COUNT_ACTIVE_PAYMENT_METHODS_BY_CHANNEL_SQL, params, Long.class);
        return count != null ? count.intValue() : 0;
    }

    public record PaymentMethodRow(
            UUID id, String countryCode, String platform,
            String methodCode, String displayName,
            String paymentChannel, String paymentMethod,
            String paymentInstructions, boolean isActive, int displayOrder,
            java.util.List<java.util.Map<String, Object>> verificationParams,
            String logoUrl
    ) {}

    public List<PaymentMethodRow> findActivePaymentMethods(String countryCode, String platform) {
        var params = new MapSqlParameterSource()
                .addValue("countryCode", countryCode)
                .addValue("platform", platform);
        return jdbc.query(FIND_PAYMENT_METHODS_SQL, params, this::mapPaymentMethodRow);
    }

    public Optional<PaymentMethodRow> findPaymentMethodById(UUID id) {
        return jdbc.query(FIND_PAYMENT_METHOD_BY_ID_SQL, Map.of("id", id), this::mapPaymentMethodRow)
                .stream().findFirst();
    }

    public List<String> findDistinctPaymentChannels(String countryCode, String platform) {
        var params = new MapSqlParameterSource()
                .addValue("countryCode", countryCode)
                .addValue("platform", platform);
        return jdbc.queryForList(FIND_DISTINCT_PAYMENT_CHANNELS_SQL, params, String.class);
    }

    public List<PaymentMethodRow> findActivePaymentMethodsByChannel(String countryCode, String platform, String channel) {
        var params = new MapSqlParameterSource()
                .addValue("countryCode", countryCode)
                .addValue("platform", platform)
                .addValue("paymentChannel", channel);
        return jdbc.query(FIND_PAYMENT_METHODS_BY_CHANNEL_SQL, params, this::mapPaymentMethodRow);
    }

    public int countActivePaymentMethods(String countryCode, String platform) {
        var params = Map.of("countryCode", countryCode, "platform", platform);
        Long count = jdbc.queryForObject(COUNT_ACTIVE_PAYMENT_METHODS_SQL, params, Long.class);
        return count != null ? count.intValue() : 0;
    }

    private PaymentMethodRow mapPaymentMethodRow(ResultSet rs, int rowNum) throws SQLException {
        return new PaymentMethodRow(
                rs.getObject("id", UUID.class),
                rs.getString("country_code"),
                rs.getString("platform"),
                rs.getString("method_code"),
                rs.getString("display_name"),
                rs.getString("payment_channel"),
                rs.getString("payment_method"),
                rs.getString("payment_instructions"),
                rs.getBoolean("is_active"),
                rs.getInt("display_order"),
                rs.getObject("verification_params") != null
                        ? parseJsonbArray(rs.getString("verification_params"))
                        : null,
                rs.getString("logo_url")
        );
    }

    // ── Payment orders ──────────────────────────────────────────────────────

    private static final String ORDER_SELECT = """
            SELECT po.id, po.user_id, po.payment_offer_id, po.payment_method_id,
                   po.order_reference, po.status, po.status_reason,
                   po.expected_amount_minor_units, po.expected_currency,
                   po.payment_instruction_snapshot,
                   po.provider_checkout_url, po.provider_order_reference,
                   po.expires_at, po.created_at, po.updated_at,
                   po.manual_payment_reference, po.manual_payment_reference_normalized,
                   po.provider_verification_request_id, po.verification_count,
                   pm.payment_channel, pm.payment_method, pm.method_code AS payment_method_code,
                   pm.display_name AS payment_method_display_name
            FROM payment_orders po
            LEFT JOIN payment_methods pm ON pm.id = po.payment_method_id
            """;

    private static final String INSERT_ORDER_SQL = """
            INSERT INTO payment_orders
                (id, user_id, payment_offer_id, payment_method_id, order_reference, status,
                 expected_amount_minor_units, expected_currency,
                 payment_instruction_snapshot, provider_checkout_url, expires_at,
                 idempotency_key, verification_count)
            VALUES
                (:id, :userId, :paymentOfferId, :paymentMethodId, :orderReference, :status,
                 :expectedAmount, :expectedCurrency,
                 :instructionSnapshot::jsonb, :providerCheckoutUrl, :expiresAt,
                 :idempotencyKey, :verificationCount)
            RETURNING id
            """;

    private static final String FIND_ORDER_BY_ID_SQL =
            ORDER_SELECT + "WHERE po.id = :orderId";

    private static final String FIND_ORDER_BY_IDEMPOTENCY_SQL =
            ORDER_SELECT + "WHERE po.user_id = :userId AND po.idempotency_key = :idempotencyKey";

    private static final String FIND_ORDER_BY_REFERENCE_SQL =
            ORDER_SELECT + "WHERE po.order_reference = :orderReference";

    private static final String FIND_ORDER_BY_MANUAL_REF_SQL =
            ORDER_SELECT + """
            WHERE po.payment_method_id = :methodId
              AND po.manual_payment_reference_normalized = :normalizedRef
            ORDER BY po.created_at DESC LIMIT 1
            """;

    private static final String INSERT_MANUAL_TRANSFER_ORDER_SQL = """
            INSERT INTO payment_orders
                (user_id, payment_offer_id, payment_method_id, order_reference, status,
                 expected_amount_minor_units, expected_currency,
                 payment_instruction_snapshot, expires_at, idempotency_key,
                 manual_payment_reference, manual_payment_reference_normalized,
                 verification_count)
            VALUES
                (:userId, :paymentOfferId, :paymentMethodId, :orderReference, :status,
                 :expectedAmount, :expectedCurrency,
                 :instructionSnapshot::jsonb, :expiresAt, :idempotencyKey,
                 :manualPaymentReference, :manualPaymentReferenceNormalized,
                 :verificationCount)
            RETURNING id
            """;

    private static final String UPDATE_PROVIDER_VERIFICATION_REQUEST_SQL = """
            UPDATE payment_orders SET provider_verification_request_id = :requestId, updated_at = NOW()
            WHERE id = :orderId
            """;

    private static final String UPDATE_ORDER_STATUS_SQL = """
            UPDATE payment_orders SET status = :status, status_reason = :reason, updated_at = NOW()
            WHERE id = :orderId
            """;

    private static final String UPDATE_ORDER_STATUS_WITH_CHECKOUT_SQL = """
            UPDATE payment_orders
            SET status = :status, provider_checkout_url = :checkoutUrl,
                provider_order_reference = :providerRef, updated_at = NOW()
            WHERE id = :orderId
            """;

    public record OrderRow(
            UUID id, UUID userId, UUID paymentOfferId, UUID paymentMethodId,
            String orderReference, String status, String statusReason,
            int expectedAmountMinorUnits, String expectedCurrency,
            String paymentChannel, String paymentMethod, String methodCode, String paymentMethodDisplayName,
            String providerCheckoutUrl, String providerOrderReference,
            Instant expiresAt, Instant createdAt, Instant updatedAt,
            String manualPaymentReference, String manualPaymentReferenceNormalized,
            String providerVerificationRequestId, Integer verificationCount
    ) {}

    public OrderRow insertOrder(UUID orderId, UUID userId, UUID paymentOfferId, UUID paymentMethodId,
                                String orderReference, String status,
                                int expectedAmount, String expectedCurrency,
                                String instructionSnapshot, String providerCheckoutUrl,
                                Instant expiresAt, String idempotencyKey) {
        var params = new MapSqlParameterSource()
                .addValue("id", orderId)
                .addValue("userId", userId)
                .addValue("paymentOfferId", paymentOfferId)
                .addValue("paymentMethodId", paymentMethodId)
                .addValue("orderReference", orderReference)
                .addValue("status", status)
                .addValue("expectedAmount", expectedAmount)
                .addValue("expectedCurrency", expectedCurrency)
                .addValue("instructionSnapshot", instructionSnapshot)
                .addValue("providerCheckoutUrl", providerCheckoutUrl)
                .addValue("expiresAt", java.sql.Timestamp.from(expiresAt))
                .addValue("idempotencyKey", idempotencyKey)
                .addValue("verificationCount", 0);
        jdbc.queryForObject(INSERT_ORDER_SQL, params,
                (rs, rowNum) -> rs.getObject("id", UUID.class));
        return findOrderById(orderId).orElseThrow();
    }

    public Optional<OrderRow> findOrderById(UUID orderId) {
        return jdbc.query(FIND_ORDER_BY_ID_SQL, Map.of("orderId", orderId), this::mapOrderRow)
                .stream().findFirst();
    }

    public Optional<OrderRow> findOrderByIdempotency(UUID userId, String idempotencyKey) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("idempotencyKey", idempotencyKey);
        return jdbc.query(FIND_ORDER_BY_IDEMPOTENCY_SQL, params, this::mapOrderRow)
                .stream().findFirst();
    }

    public Optional<OrderRow> findOrderByReference(String orderReference) {
        return jdbc.query(FIND_ORDER_BY_REFERENCE_SQL, Map.of("orderReference", orderReference), this::mapOrderRow)
                .stream().findFirst();
    }

    public void updateOrderStatus(UUID orderId, String status) {
        updateOrderStatus(orderId, status, null);
    }

    public void updateOrderStatus(UUID orderId, String status, String reason) {
        jdbc.update(UPDATE_ORDER_STATUS_SQL, new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("status", status)
                .addValue("reason", reason));
    }

    public OrderRow insertManualTransferOrder(UUID userId, UUID paymentOfferId, UUID paymentMethodId,
                                              String orderReference, String status,
                                              int expectedAmount, String expectedCurrency,
                                              String instructionSnapshot, Instant expiresAt,
                                              String idempotencyKey,
                                              String manualPaymentReference,
                                              String manualPaymentReferenceNormalized) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("paymentOfferId", paymentOfferId)
                .addValue("paymentMethodId", paymentMethodId)
                .addValue("orderReference", orderReference)
                .addValue("status", status)
                .addValue("expectedAmount", expectedAmount)
                .addValue("expectedCurrency", expectedCurrency)
                .addValue("instructionSnapshot", instructionSnapshot)
                .addValue("expiresAt", java.sql.Timestamp.from(expiresAt))
                .addValue("idempotencyKey", idempotencyKey)
                .addValue("manualPaymentReference", manualPaymentReference)
                .addValue("manualPaymentReferenceNormalized", manualPaymentReferenceNormalized)
                .addValue("verificationCount", 0);
        UUID orderId = jdbc.queryForObject(INSERT_MANUAL_TRANSFER_ORDER_SQL, params,
                (rs, rowNum) -> rs.getObject("id", UUID.class));
        return findOrderById(orderId).orElseThrow();
    }

    public Optional<OrderRow> findOrderByManualReference(UUID methodId, String normalizedRef) {
        if (normalizedRef == null || normalizedRef.isBlank()) return Optional.empty();
        var params = new MapSqlParameterSource()
                .addValue("methodId", methodId)
                .addValue("normalizedRef", normalizedRef);
        return jdbc.query(FIND_ORDER_BY_MANUAL_REF_SQL, params, this::mapOrderRow)
                .stream().findFirst();
    }

    public void updateOrderProviderVerificationRequestId(UUID orderId, String requestId) {
        jdbc.update(UPDATE_PROVIDER_VERIFICATION_REQUEST_SQL, new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("requestId", requestId));
    }

    public void updateOrderWithCheckout(UUID orderId, String status, String checkoutUrl, String providerRef) {
        jdbc.update(UPDATE_ORDER_STATUS_WITH_CHECKOUT_SQL, new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("status", status)
                .addValue("checkoutUrl", checkoutUrl)
                .addValue("providerRef", providerRef));
    }

    // ── Payment proofs ──────────────────────────────────────────────────────

    private static final String INSERT_PROOF_SQL = """
            INSERT INTO payment_proofs
                (payment_order_id, proof_type, payment_network, transaction_reference,
                 receipt_storage_bucket, receipt_storage_path,
                 submitted_amount_minor_units, submitted_currency)
            VALUES
                (:orderId, :proofType, :paymentNetwork, :transactionReference,
                 :receiptBucket, :receiptPath, :submittedAmount, :submittedCurrency)
            RETURNING id
            """;

    public UUID insertProof(UUID orderId, String proofType, String paymentNetwork,
                            String transactionReference, String receiptBucket, String receiptPath,
                            Integer submittedAmount, String submittedCurrency) {
        var params = new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("proofType", proofType)
                .addValue("paymentNetwork", paymentNetwork)
                .addValue("transactionReference", transactionReference)
                .addValue("receiptBucket", receiptBucket)
                .addValue("receiptPath", receiptPath)
                .addValue("submittedAmount", submittedAmount)
                .addValue("submittedCurrency", submittedCurrency);
        return jdbc.queryForObject(INSERT_PROOF_SQL, params, (rs, rowNum) -> rs.getObject("id", UUID.class));
    }

    // ── Payment verification attempts ───────────────────────────────────────

    private static final String INSERT_VERIFICATION_ATTEMPT_SQL = """
            INSERT INTO payment_verification_attempts
                (payment_order_id, payment_proof_id, verification_method, status, raw_response)
            VALUES
                (:orderId, :proofId, :verificationMethod, :status, :rawResponse::jsonb)
            RETURNING id
            """;

    private static final String UPDATE_VERIFICATION_SQL = """
            UPDATE payment_verification_attempts
            SET status = :status,
                verified_amount_minor_units = :verifiedAmount,
                verified_currency = :verifiedCurrency,
                verified_recipient_reference = :verifiedRecipient,
                verified_paid_at = :verifiedPaidAt,
                provider_verification_reference = :providerRef,
                raw_response = :rawResponse::jsonb,
                verified_by_admin_id = :adminId,
                admin_decision_note = :adminNote,
                updated_at = NOW()
            WHERE id = :id
            """;

    private static final String UPDATE_VERIFICATION_VERIFY_ET_SQL = """
            UPDATE payment_verification_attempts
            SET verify_et_request_id      = :verifyEtRequestId,
                verify_et_idempotency_key = :idempotencyKey,
                status                    = :status,
                raw_response              = :rawResponse::jsonb,
                updated_at                = NOW()
            WHERE id = :id
            """;

    private static final String FINALIZE_VERIFICATION_VERIFY_ET_SQL = """
            UPDATE payment_verification_attempts
            SET status                    = :status,
                verified_amount_minor_units = :verifiedAmount,
                verified_currency          = :verifiedCurrency,
                provider_verification_reference = :providerRef,
                settlement_account_matched = :settlementMatched,
                confirmed_before           = :confirmedBefore,
                raw_response               = :rawResponse::jsonb,
                updated_at                 = NOW()
            WHERE id = :id
            """;

    private static final String LOCK_ORDER_FOR_UPDATE_SQL =
            ORDER_SELECT + "WHERE po.id = :orderId FOR UPDATE";

    public Optional<OrderRow> lockOrderForUpdate(UUID orderId) {
        return jdbc.query(LOCK_ORDER_FOR_UPDATE_SQL, Map.of("orderId", orderId), this::mapOrderRow)
                .stream().findFirst();
    }

    private static final String FIND_VERIFICATION_BY_VERIFY_ET_ID_SQL = """
            SELECT pva.id, pva.payment_order_id, pva.status,
                   po.user_id AS order_user_id,
                   po.expected_amount_minor_units AS order_expected_amount,
                   po.expected_currency AS order_expected_currency,
                   po.created_at AS order_created_at,
                   pm.method_code AS order_method_code
            FROM payment_verification_attempts pva
            JOIN payment_orders po ON po.id = pva.payment_order_id
            LEFT JOIN payment_methods pm ON pm.id = po.payment_method_id
            WHERE pva.verify_et_request_id = :verifyEtRequestId
            LIMIT 1
            """;

    public record VerificationAttemptRow(
            UUID id, UUID orderId, UUID userId, String status,
            Integer orderExpectedAmount, String orderExpectedCurrency,
            Instant orderCreatedAt, String orderMethodCode
    ) {}

    public Optional<VerificationAttemptRow> findVerificationByVerifyEtRequestId(String verifyEtRequestId) {
        return jdbc.query(FIND_VERIFICATION_BY_VERIFY_ET_ID_SQL,
                Map.of("verifyEtRequestId", verifyEtRequestId),
                (rs, rowNum) -> new VerificationAttemptRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("payment_order_id", UUID.class),
                        rs.getObject("order_user_id", UUID.class),
                        rs.getString("status"),
                        rs.getObject("order_expected_amount") != null ? rs.getInt("order_expected_amount") : null,
                        rs.getString("order_expected_currency"),
                        toInstant(rs, "order_created_at"),
                        rs.getString("order_method_code")))
                .stream().findFirst();
    }

    private static final String FIND_DUPLICATE_PROVIDER_REF_SQL = """
            SELECT COUNT(*) FROM payment_verification_attempts pva
            JOIN payment_orders po ON po.id = pva.payment_order_id
            WHERE pva.provider_verification_reference = :providerRef
              AND pva.status IN ('VERIFIED', 'MANUAL_REVIEW')
              AND po.id != :excludeOrderId
            """;

    private static final String FIND_VERIFY_ET_REQUEST_ID_SQL = """
            SELECT verify_et_request_id FROM payment_verification_attempts
            WHERE payment_order_id = :orderId AND verify_et_request_id IS NOT NULL
            ORDER BY created_at DESC LIMIT 1
            """;

    public Optional<String> findVerifyEtRequestIdByOrderId(UUID orderId) {
        return jdbc.queryForList(FIND_VERIFY_ET_REQUEST_ID_SQL,
                Map.of("orderId", orderId), String.class)
                .stream().findFirst();
    }

    public boolean existsVerifiedProviderReference(String providerRef, UUID excludeOrderId) {
        if (providerRef == null || providerRef.isBlank()) return false;
        var params = new MapSqlParameterSource()
                .addValue("providerRef", providerRef)
                .addValue("excludeOrderId", excludeOrderId);
        Long count = jdbc.queryForObject(FIND_DUPLICATE_PROVIDER_REF_SQL, params, Long.class);
        return count != null && count > 0;
    }

    private static final String INCREMENT_VERIFICATION_COUNT_SQL = """
            UPDATE payment_orders
            SET verification_count = verification_count + 1, updated_at = NOW()
            WHERE id = :orderId
            """;

    public int incrementVerificationCount(UUID orderId) {
        var params = new MapSqlParameterSource("orderId", orderId);
        int rows = jdbc.update(INCREMENT_VERIFICATION_COUNT_SQL, params);
        log.debug("incremented verification_count for order={}, rowsUpdated={}", orderId, rows);
        return rows;
    }

    public UUID insertVerificationAttempt(UUID orderId, UUID proofId, String verificationMethod,
                                          String status, String rawResponse) {
        var params = new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("proofId", proofId)
                .addValue("verificationMethod", verificationMethod)
                .addValue("status", status)
                .addValue("rawResponse", rawResponse != null ? rawResponse : "{}");
        return jdbc.queryForObject(INSERT_VERIFICATION_ATTEMPT_SQL, params,
                (rs, rowNum) -> rs.getObject("id", UUID.class));
    }

    public void updateVerificationAttemptWithVerifyEtRequest(UUID id, String verifyEtRequestId,
                                                              String idempotencyKey, String status,
                                                              String rawResponse) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("verifyEtRequestId", verifyEtRequestId)
                .addValue("idempotencyKey", idempotencyKey)
                .addValue("status", status)
                .addValue("rawResponse", rawResponse != null ? rawResponse : "{}");
        jdbc.update(UPDATE_VERIFICATION_VERIFY_ET_SQL, params);
    }

    public void finalizeVerificationAttemptVerifyEt(UUID id, String status, Integer verifiedAmount,
                                                     String verifiedCurrency, String providerRef,
                                                     Boolean settlementMatched, Boolean confirmedBefore,
                                                     String rawResponse) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", status)
                .addValue("verifiedAmount", verifiedAmount)
                .addValue("verifiedCurrency", verifiedCurrency)
                .addValue("providerRef", providerRef)
                .addValue("settlementMatched", settlementMatched)
                .addValue("confirmedBefore", confirmedBefore)
                .addValue("rawResponse", rawResponse != null ? rawResponse : "{}");
        jdbc.update(FINALIZE_VERIFICATION_VERIFY_ET_SQL, params);
    }

    public void updateVerificationAttempt(UUID id, String status, Integer verifiedAmount,
                                          String verifiedCurrency, String verifiedRecipient,
                                          Instant verifiedPaidAt, String providerRef,
                                          String rawResponse, UUID adminId, String adminNote) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", status)
                .addValue("verifiedAmount", verifiedAmount)
                .addValue("verifiedCurrency", verifiedCurrency)
                .addValue("verifiedRecipient", verifiedRecipient)
                .addValue("verifiedPaidAt", verifiedPaidAt != null ? java.sql.Timestamp.from(verifiedPaidAt) : null)
                .addValue("providerRef", providerRef)
                .addValue("rawResponse", rawResponse != null ? rawResponse : "{}")
                .addValue("adminId", adminId)
                .addValue("adminNote", adminNote);
        jdbc.update(UPDATE_VERIFICATION_SQL, params);
    }

    // ── User subscription ───────────────────────────────────────────────────

    /** Legacy upsert used by Chapa/Telebirr/manual payment flows. */
    private static final String UPSERT_SUBSCRIPTION_SQL = """
            INSERT INTO user_subscriptions
                (user_id, plan_id, provider, provider_subscription_id, payment_offer_id,
                 provider_subscription_reference, status, auto_renew,
                 started_at, current_period_start, current_period_end)
            VALUES
                (:userId, :planId, :provider, :providerSubId, :paymentOfferId,
                 :providerSubRef, :status, :autoRenew,
                 :startedAt, :periodStart, :periodEnd)
            ON CONFLICT (user_id) WHERE status IN ('ACTIVE', 'PENDING_VERIFICATION')
            DO UPDATE SET
                status = EXCLUDED.status,
                auto_renew = EXCLUDED.auto_renew,
                plan_id = EXCLUDED.plan_id,
                provider = EXCLUDED.provider,
                provider_subscription_id = EXCLUDED.provider_subscription_id,
                provider_subscription_reference = EXCLUDED.provider_subscription_reference,
                payment_offer_id = EXCLUDED.payment_offer_id,
                current_period_start = EXCLUDED.current_period_start,
                current_period_end = EXCLUDED.current_period_end,
                updated_at = NOW()
            RETURNING id
            """;

    public UUID upsertSubscription(UUID userId, UUID planId, String provider,
                                   String providerSubId, UUID paymentOfferId,
                                   String providerSubRef, String status, boolean autoRenew,
                                   Instant startedAt, Instant periodStart, Instant periodEnd) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("planId", planId)
                .addValue("provider", provider)
                .addValue("providerSubId", providerSubId)
                .addValue("paymentOfferId", paymentOfferId)
                .addValue("providerSubRef", providerSubRef)
                .addValue("status", status)
                .addValue("autoRenew", autoRenew)
                .addValue("startedAt", java.sql.Timestamp.from(startedAt))
                .addValue("periodStart", java.sql.Timestamp.from(periodStart))
                .addValue("periodEnd", java.sql.Timestamp.from(periodEnd));
        return jdbc.queryForObject(UPSERT_SUBSCRIPTION_SQL, params,
                (rs, rowNum) -> rs.getObject("id", UUID.class));
    }

    // ── RevenueCat subscription management with row locking ─────────────────

    public record SubscriptionRow(
            UUID id, String providerSubscriptionId, String providerSubscriptionReference,
            UUID planId, UUID paymentOfferId, String provider, String status, boolean autoRenew,
            Instant periodStart, Instant periodEnd
    ) {}

    private static final String SUB_SELECT_COLS = """
            id, provider_subscription_id, provider_subscription_reference,
            plan_id, payment_offer_id, provider, status, auto_renew,
            current_period_start, current_period_end
            """;

    private SubscriptionRow mapSubscriptionRow(ResultSet rs) throws SQLException {
        return new SubscriptionRow(
                rs.getObject("id", UUID.class),
                rs.getString("provider_subscription_id"),
                rs.getString("provider_subscription_reference"),
                rs.getObject("plan_id", UUID.class),
                rs.getObject("payment_offer_id", UUID.class),
                rs.getString("provider"),
                rs.getString("status"),
                rs.getBoolean("auto_renew"),
                toInstant(rs, "current_period_start"),
                toInstant(rs, "current_period_end")
        );
    }

    private static final String LOCK_ACTIVE_SUB_SQL = """
            SELECT %s FROM user_subscriptions
            WHERE user_id = :userId AND status IN ('ACTIVE', 'PENDING_VERIFICATION')
            FOR UPDATE
            """.formatted(SUB_SELECT_COLS);

    public Optional<SubscriptionRow> lockActiveSubscriptionForUpdate(UUID userId) {
        return jdbc.query(LOCK_ACTIVE_SUB_SQL, Map.of("userId", userId),
                (rs, rowNum) -> mapSubscriptionRow(rs)).stream().findFirst();
    }

    private static final String FIND_SUB_BY_PROVIDER_SUB_ID_SQL = """
            SELECT %s FROM user_subscriptions
            WHERE user_id = :userId AND provider = :provider AND provider_subscription_id = :providerSubId
            ORDER BY updated_at DESC LIMIT 1
            """.formatted(SUB_SELECT_COLS);

    public Optional<SubscriptionRow> findSubscriptionByProviderSubId(UUID userId, String provider,
                                                                     String providerSubId) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("provider", provider)
                .addValue("providerSubId", providerSubId);
        return jdbc.query(FIND_SUB_BY_PROVIDER_SUB_ID_SQL, params,
                (rs, rowNum) -> mapSubscriptionRow(rs)).stream().findFirst();
    }

    private static final String INSERT_SUB_SQL = """
            INSERT INTO user_subscriptions
                (user_id, plan_id, provider, provider_subscription_id, payment_offer_id,
                 provider_subscription_reference, status, auto_renew,
                 started_at, current_period_start, current_period_end)
            VALUES
                (:userId, :planId, :provider, :providerSubId, :paymentOfferId,
                 :providerSubRef, :status, :autoRenew,
                 :startedAt, :periodStart, :periodEnd)
            RETURNING id
            """;

    public UUID insertSubscription(UUID userId, UUID planId, String provider,
                                   String providerSubId, UUID paymentOfferId,
                                   String providerSubRef, String status, boolean autoRenew,
                                   Instant startedAt, Instant periodStart, Instant periodEnd) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("planId", planId)
                .addValue("provider", provider)
                .addValue("providerSubId", providerSubId)
                .addValue("paymentOfferId", paymentOfferId)
                .addValue("providerSubRef", providerSubRef)
                .addValue("status", status)
                .addValue("autoRenew", autoRenew)
                .addValue("startedAt", java.sql.Timestamp.from(startedAt))
                .addValue("periodStart", java.sql.Timestamp.from(periodStart))
                .addValue("periodEnd", java.sql.Timestamp.from(periodEnd));
        return jdbc.queryForObject(INSERT_SUB_SQL, params,
                (rs, rowNum) -> rs.getObject("id", UUID.class));
    }

    private static final String UPDATE_SUB_BY_ID_SQL = """
            UPDATE user_subscriptions SET
                plan_id = :planId,
                provider = :provider,
                provider_subscription_id = :providerSubId,
                provider_subscription_reference = :providerSubRef,
                payment_offer_id = :paymentOfferId,
                status = :status,
                auto_renew = :autoRenew,
                current_period_start = :periodStart,
                current_period_end = :periodEnd,
                updated_at = NOW()
            WHERE id = :id
            """;

    public void updateSubscriptionById(UUID id, UUID planId, String provider,
                                       String providerSubId, UUID paymentOfferId,
                                       String providerSubRef, String status, boolean autoRenew,
                                       Instant periodStart, Instant periodEnd) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("planId", planId)
                .addValue("provider", provider)
                .addValue("providerSubId", providerSubId)
                .addValue("paymentOfferId", paymentOfferId)
                .addValue("providerSubRef", providerSubRef)
                .addValue("status", status)
                .addValue("autoRenew", autoRenew)
                .addValue("periodStart", java.sql.Timestamp.from(periodStart))
                .addValue("periodEnd", java.sql.Timestamp.from(periodEnd));
        jdbc.update(UPDATE_SUB_BY_ID_SQL, params);
    }

    private static final String EXPIRE_SUB_BY_ID_SQL = """
            UPDATE user_subscriptions SET
                status = 'EXPIRED', ended_at = NOW(), updated_at = NOW()
            WHERE id = :id
            """;

    public void expireSubscriptionById(UUID id) {
        jdbc.update(EXPIRE_SUB_BY_ID_SQL, Map.of("id", id));
    }

    // ── Account deletion: find and cancel all active subscriptions ──────────

    private static final String FIND_ACTIVE_SUBS_FOR_USER_SQL = """
            SELECT id, provider_subscription_id, provider, status, auto_renew
            FROM user_subscriptions
            WHERE user_id = :userId
              AND status IN ('ACTIVE', 'GRACE_PERIOD', 'PAST_DUE', 'UNPAID', 'PENDING_VERIFICATION')
            """;

    public record ActiveSubscriptionRow(
            UUID id, String providerSubscriptionId, String provider,
            String status, boolean autoRenew
    ) {}

    public List<ActiveSubscriptionRow> findActiveSubscriptionsForUser(UUID userId) {
        return jdbc.query(FIND_ACTIVE_SUBS_FOR_USER_SQL, Map.of("userId", userId),
                (rs, rowNum) -> new ActiveSubscriptionRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("provider_subscription_id"),
                        rs.getString("provider"),
                        rs.getString("status"),
                        rs.getBoolean("auto_renew")
                ));
    }

    private static final String CANCEL_ALL_ACTIVE_SUBS_SQL = """
            UPDATE user_subscriptions
            SET status              = 'CANCELED',
                auto_renew          = FALSE,
                cancelled_at        = NOW(),
                ended_at            = NOW(),
                current_period_end  = NOW(),
                updated_at          = NOW()
            WHERE user_id = :userId
              AND status IN ('ACTIVE', 'GRACE_PERIOD', 'PAST_DUE', 'UNPAID', 'PENDING_VERIFICATION')
            """;

    public int cancelAllActiveSubscriptions(UUID userId) {
        return jdbc.update(CANCEL_ALL_ACTIVE_SUBS_SQL, Map.of("userId", userId));
    }

    private static final String CANCEL_PENDING_ORDERS_SQL = """
            UPDATE payment_orders
            SET status       = 'CANCELLED',
                status_reason = 'ACCOUNT_DELETED',
                updated_at    = NOW()
            WHERE user_id = :userId
              AND status IN ('CREATED', 'AWAITING_PAYMENT', 'RECEIPT_SUBMITTED',
                             'REVIEW_REQUIRED', 'VERIFICATION_PENDING')
            """;

    public int cancelPendingOrders(UUID userId) {
        return jdbc.update(CANCEL_PENDING_ORDERS_SQL, Map.of("userId", userId));
    }

    // ── Concurrent-safe subscription locking ────────────────────────────────

    private static final String LOCK_ALL_SUBS_SQL = """
            SELECT %s FROM user_subscriptions
            WHERE user_id = :userId
            ORDER BY created_at
            FOR UPDATE
            """.formatted(SUB_SELECT_COLS);

    public List<SubscriptionRow> lockAllSubscriptionsForUpdate(UUID userId) {
        return jdbc.query(LOCK_ALL_SUBS_SQL, Map.of("userId", userId),
                (rs, rowNum) -> mapSubscriptionRow(rs));
    }

    private static final String LOCK_USER_ROW_SQL = """
            SELECT 1 FROM app_users WHERE id = :userId FOR UPDATE
            """;

    public void lockUserRowForUpdate(UUID userId) {
        jdbc.queryForObject(LOCK_USER_ROW_SQL, Map.of("userId", userId), Integer.class);
    }

    private static final String REPLACE_SUB_BY_ID_SQL = """
            UPDATE user_subscriptions SET
                status = 'EXPIRED', ended_at = NOW(), updated_at = NOW()
            WHERE id = :id
            """;

    public void markSubscriptionReplaced(UUID id) {
        jdbc.update(REPLACE_SUB_BY_ID_SQL, Map.of("id", id));
    }

    // ── Legacy subscription update methods (by provider_subscription_id) ────

    private static final String UPDATE_SUB_STATUS_SQL = """
            UPDATE user_subscriptions SET status = :status, updated_at = NOW()
            WHERE provider_subscription_id = :providerSubId
            """;

    private static final String UPDATE_SUB_CANCEL_SQL = """
            UPDATE user_subscriptions
            SET auto_renew = FALSE, cancelled_at = NOW(), updated_at = NOW()
            WHERE provider_subscription_id = :providerSubId
            """;

    private static final String UPDATE_SUB_PERIOD_END_SQL = """
            UPDATE user_subscriptions
            SET current_period_end = :periodEnd, updated_at = NOW()
            WHERE provider_subscription_id = :providerSubId
            """;

    public void updateSubscriptionStatus(String providerSubId, String status) {
        jdbc.update(UPDATE_SUB_STATUS_SQL, Map.of("providerSubId", providerSubId, "status", status));
    }

    public void cancelSubscription(String providerSubId) {
        jdbc.update(UPDATE_SUB_CANCEL_SQL, Map.of("providerSubId", providerSubId));
    }

    public void updateSubscriptionPeriodEnd(String providerSubId, Instant periodEnd) {
        jdbc.update(UPDATE_SUB_PERIOD_END_SQL, Map.of(
                "providerSubId", providerSubId,
                "periodEnd", java.sql.Timestamp.from(periodEnd)));
    }

    // ── Payment event status management ─────────────────────────────────────

    public record EventStatusRow(UUID id, String processingStatus) {}

    private static final String FIND_EVENT_STATUS_SQL = """
            SELECT id, processing_status FROM payment_events
            WHERE provider = :provider AND provider_event_id = :providerEventId
            """;

    public Optional<EventStatusRow> findEventStatus(String provider, String providerEventId) {
        return jdbc.query(FIND_EVENT_STATUS_SQL,
                Map.of("provider", provider, "providerEventId", providerEventId),
                (rs, rowNum) -> new EventStatusRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("processing_status")))
                .stream().findFirst();
    }

    private static final String UPDATE_EVENT_STATUS_SQL = """
            UPDATE payment_events SET
                processing_status = :status,
                processed_at = CASE WHEN :status = 'PROCESSED' THEN NOW() ELSE processed_at END,
                processing_error = :error
            WHERE id = :id
            """;

    public void updateEventStatus(UUID eventDbId, String status, String error) {
        var params = new MapSqlParameterSource()
                .addValue("id", eventDbId)
                .addValue("status", status)
                .addValue("error", error);
        jdbc.update(UPDATE_EVENT_STATUS_SQL, params);
    }

    private static final String UPDATE_EVENT_LINKS_SQL = """
            UPDATE payment_events SET
                subscription_id = :subscriptionId,
                transaction_id = :transactionId
            WHERE id = :id
            """;

    public void updateEventLinks(UUID eventDbId, UUID subscriptionId, UUID transactionId) {
        var params = new MapSqlParameterSource()
                .addValue("id", eventDbId)
                .addValue("subscriptionId", subscriptionId)
                .addValue("transactionId", transactionId);
        jdbc.update(UPDATE_EVENT_LINKS_SQL, params);
    }

    // ── Idempotent transaction lookup ───────────────────────────────────────

    private static final String FIND_TX_BY_PROVIDER_TX_ID_SQL = """
            SELECT id FROM transactions
            WHERE provider = :provider AND provider_transaction_id = :providerTxId
            """;

    public Optional<UUID> findTransactionByProviderTxId(String provider, String providerTxId) {
        return jdbc.query(FIND_TX_BY_PROVIDER_TX_ID_SQL,
                Map.of("provider", provider, "providerTxId", providerTxId),
                (rs, rowNum) -> rs.getObject("id", UUID.class))
                .stream().findFirst();
    }

    // ── Transactions ────────────────────────────────────────────────────────

    private static final String INSERT_TRANSACTION_SQL = """
            INSERT INTO transactions
                (user_id, subscription_id, payment_order_id, payment_offer_id,
                 related_transaction_id, payment_purpose, transaction_type,
                 amount_minor_units, currency, provider, provider_transaction_id,
                 verification_provider, country_code, status)
            VALUES
                (:userId, :subscriptionId, :paymentOrderId, :paymentOfferId,
                 :relatedTransactionId, :paymentPurpose, :transactionType,
                 :amount, :currency, :provider, :providerTransactionId,
                 :verificationProvider, :countryCode, :status)
            RETURNING id
            """;

    public UUID insertTransaction(UUID userId, UUID subscriptionId, UUID paymentOrderId,
                                  UUID paymentOfferId, UUID relatedTransactionId,
                                  String paymentPurpose, String transactionType,
                                  int amount, String currency, String provider,
                                  String providerTransactionId, String verificationProvider,
                                  String countryCode, String status) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("subscriptionId", subscriptionId)
                .addValue("paymentOrderId", paymentOrderId)
                .addValue("paymentOfferId", paymentOfferId)
                .addValue("relatedTransactionId", relatedTransactionId)
                .addValue("paymentPurpose", paymentPurpose)
                .addValue("transactionType", transactionType)
                .addValue("amount", amount)
                .addValue("currency", currency)
                .addValue("provider", provider)
                .addValue("providerTransactionId", providerTransactionId)
                .addValue("verificationProvider", verificationProvider)
                .addValue("countryCode", countryCode)
                .addValue("status", status);
        return jdbc.queryForObject(INSERT_TRANSACTION_SQL, params,
                (rs, rowNum) -> rs.getObject("id", UUID.class));
    }

    // ── Payment events ──────────────────────────────────────────────────────

    private static final String LOG_EVENT_SQL = """
            INSERT INTO payment_events
                (provider, provider_event_id, event_type, raw_payload, processing_status,
                 user_id, amount_minor_units, currency, signature_verified_at)
            VALUES
                (:provider, :providerEventId, :eventType, :rawPayload::jsonb, :processingStatus,
                 :userId, :amountMinorUnits, :currency, :signatureVerifiedAt)
            ON CONFLICT (provider, provider_event_id) DO NOTHING
            RETURNING id
            """;

    public Optional<UUID> logEvent(String provider, String providerEventId, String eventType,
                                   String rawPayload, String processingStatus) {
        return logEvent(provider, providerEventId, eventType, rawPayload, processingStatus,
                null, null, null, null);
    }

    public Optional<UUID> logEvent(String provider, String providerEventId, String eventType,
                                   String rawPayload, String processingStatus,
                                   UUID userId, Integer amountMinorUnits, String currency,
                                   Instant signatureVerifiedAt) {
        var params = new MapSqlParameterSource()
                .addValue("provider", provider)
                .addValue("providerEventId", providerEventId)
                .addValue("eventType", eventType)
                .addValue("rawPayload", rawPayload)
                .addValue("processingStatus", processingStatus)
                .addValue("userId", userId)
                .addValue("amountMinorUnits", amountMinorUnits)
                .addValue("currency", currency)
                .addValue("signatureVerifiedAt", signatureVerifiedAt != null ? java.sql.Timestamp.from(signatureVerifiedAt) : null);
        return jdbc.query(LOG_EVENT_SQL, params, (rs, rowNum) -> rs.getObject("id", UUID.class))
                .stream().findFirst();
    }

    // ── Active subscription check ───────────────────────────────────────────

    private static final String FIND_ACTIVE_SUB_SQL = """
            SELECT us.id, us.plan_id, us.status, us.auto_renew,
                   us.current_period_start, us.current_period_end,
                   us.provider,
                   sp.plan_code, sp.features,
                   sprod.billing_interval_unit, sprod.billing_interval_count
            FROM user_subscriptions us
            JOIN subscription_plans sp ON sp.id = us.plan_id
            LEFT JOIN payment_offers po ON po.id = us.payment_offer_id
            LEFT JOIN subscription_products sprod ON sprod.id = po.subscription_product_id
            WHERE us.user_id = :userId
              AND us.status IN ('ACTIVE', 'GRACE_PERIOD')
              AND us.current_period_end > NOW()
            ORDER BY us.current_period_end DESC
            LIMIT 1
            """;

    public record ActiveSubRow(
            UUID id, UUID planId, String status, boolean autoRenew,
            Instant periodStart, Instant periodEnd,
            String provider, String planCode, String features,
            String billingIntervalUnit, Integer billingIntervalCount
    ) {}

    public Optional<ActiveSubRow> findActiveSubscription(UUID userId) {
        return jdbc.query(FIND_ACTIVE_SUB_SQL, Map.of("userId", userId), (rs, rowNum) -> new ActiveSubRow(
                rs.getObject("id", UUID.class),
                rs.getObject("plan_id", UUID.class),
                rs.getString("status"),
                rs.getBoolean("auto_renew"),
                toInstant(rs, "current_period_start"),
                toInstant(rs, "current_period_end"),
                rs.getString("provider"),
                rs.getString("plan_code"),
                rs.getString("features"),
                rs.getString("billing_interval_unit"),
                rs.getObject("billing_interval_count") != null ? rs.getInt("billing_interval_count") : null
        )).stream().findFirst();
    }

    // ── Unlimited entitlement types for active subscription ─────────────────

    private static final String UNLIMITED_ENTITLEMENTS_SQL = """
            SELECT fa.code AS limit_type
            FROM user_subscriptions us
            JOIN subscription_plan_limit_and_cost splac ON splac.subscription_plan_id = us.plan_id
            JOIN feature_actions fa ON fa.id = splac.feature_action_id
            WHERE us.user_id = :userId
              AND us.status = 'ACTIVE'
              AND splac.limit_value IS NULL
              AND fa.code IN ('BOOST', 'SUPER_LIKE', 'REWIND')
            """;

    public java.util.Set<String> getUnlimitedEntitlementTypes(UUID userId) {
        return new java.util.HashSet<>(
                jdbc.queryForList(UNLIMITED_ENTITLEMENTS_SQL, Map.of("userId", userId), String.class));
    }

    // ── User country (legacy – prefer BillingMarketResolver) ────────────────

    private static final String USER_COUNTRY_SQL = """
            SELECT COALESCE(au.billing_country_code, a.country_code, 'GLOBAL') AS country_code
            FROM app_users au
            LEFT JOIN addresses a ON a.id = au.address_id
            WHERE au.id = :userId
            """;

    public String getUserCountryCode(UUID userId) {
        var results = jdbc.queryForList(USER_COUNTRY_SQL, Map.of("userId", userId));
        if (results.isEmpty()) return "GLOBAL";
        Object cc = results.get(0).get("country_code");
        return cc != null ? cc.toString() : "GLOBAL";
    }

    // ── User: list own orders (paginated) ───────────────────────────────────

    public record OrderSummaryRow(
            UUID id, UUID userId, UUID paymentOfferId, UUID paymentMethodId,
            String orderReference, String status,
            int expectedAmountMinorUnits, String expectedCurrency,
            String paymentChannel, String paymentMethod, String methodCode, String paymentMethodDisplayName,
            String productCode, String productType, String displayName,
            Instant expiresAt, Instant createdAt, Instant updatedAt,
            Integer verificationCount
    ) {}

    private static final String USER_ORDERS_SQL_BASE = """
            SELECT po.id, po.user_id, po.payment_offer_id, po.payment_method_id,
                   po.order_reference, po.status,
                   po.expected_amount_minor_units, po.expected_currency,
                   po.expires_at, po.created_at, po.updated_at,
                   po.verification_count,
                   pm.payment_channel, pm.payment_method, pm.method_code AS payment_method_code,
                   pm.display_name AS payment_method_display_name,
                   COALESCE(sp.product_code, cp.product_code) AS product_code,
                   CASE WHEN pof.subscription_product_id IS NOT NULL THEN 'SUBSCRIPTION' ELSE 'CONSUMABLE' END AS product_type,
                   COALESCE(cp.name, sp.product_code) AS display_name
            FROM payment_orders po
            LEFT JOIN payment_methods pm ON pm.id = po.payment_method_id
            LEFT JOIN payment_offers pof ON pof.id = po.payment_offer_id
            LEFT JOIN subscription_products sp ON sp.id = pof.subscription_product_id
            LEFT JOIN consumable_products cp ON cp.id = pof.consumable_product_id
            WHERE po.user_id = :userId
            """;

    private static final String COUNT_USER_ORDERS_SQL_BASE =
            "SELECT COUNT(*) FROM payment_orders WHERE user_id = :userId";

    public List<OrderSummaryRow> findOrderSummariesByUserId(UUID userId, List<String> statuses,
                                                             int pageSize, int offset) {
        var sb = new StringBuilder(USER_ORDERS_SQL_BASE);
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("pageSize", pageSize)
                .addValue("offset", offset);
        if (statuses != null && !statuses.isEmpty()) {
            sb.append(" AND po.status IN (:statuses)");
            params.addValue("statuses", statuses);
        }
        sb.append(" ORDER BY po.created_at DESC LIMIT :pageSize OFFSET :offset");
        return jdbc.query(sb.toString(), params, this::mapOrderSummaryRow);
    }

    public long countOrdersByUserId(UUID userId, List<String> statuses) {
        var sb = new StringBuilder(COUNT_USER_ORDERS_SQL_BASE);
        var params = new MapSqlParameterSource().addValue("userId", userId);
        if (statuses != null && !statuses.isEmpty()) {
            sb.append(" AND status IN (:statuses)");
            params.addValue("statuses", statuses);
        }
        Long count = jdbc.queryForObject(sb.toString(), params, Long.class);
        return count != null ? count : 0;
    }

    private OrderSummaryRow mapOrderSummaryRow(ResultSet rs, int rowNum) throws SQLException {
        return new OrderSummaryRow(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("payment_offer_id", UUID.class),
                rs.getObject("payment_method_id", UUID.class),
                rs.getString("order_reference"),
                rs.getString("status"),
                rs.getInt("expected_amount_minor_units"),
                rs.getString("expected_currency"),
                rs.getString("payment_channel"),
                rs.getString("payment_method"),
                rs.getString("payment_method_code"),
                rs.getString("payment_method_display_name"),
                rs.getString("product_code"),
                rs.getString("product_type"),
                rs.getString("display_name"),
                toInstant(rs, "expires_at"),
                toInstant(rs, "created_at"),
                toInstant(rs, "updated_at"),
                rs.getObject("verification_count") != null ? rs.getInt("verification_count") : 0
        );
    }

    // ── Admin: list payment orders ──────────────────────────────────────────

    private static final String LIST_ORDERS_SQL = """
            SELECT po.*, pm.method_code, pm.display_name AS method_display_name,
                   p.display_name AS user_display_name
            FROM payment_orders po
            LEFT JOIN payment_methods pm ON pm.id = po.payment_method_id
            LEFT JOIN profiles p ON p.user_id = po.user_id
            WHERE (:statusesEmpty OR po.status IN (:statuses))
              AND (:methodCode IS NULL OR pm.method_code = :methodCode)
              AND (:countryCode IS NULL OR EXISTS (
                  SELECT 1 FROM app_users au
                  JOIN addresses a ON a.id = au.address_id
                  WHERE au.id = po.user_id AND a.country_code = :countryCode
              ))
            ORDER BY po.created_at DESC
            LIMIT :pageSize OFFSET :offset
            """;

    private static final String COUNT_ORDERS_SQL = """
            SELECT COUNT(*) FROM payment_orders po
            LEFT JOIN payment_methods pm ON pm.id = po.payment_method_id
            WHERE (:statusesEmpty OR po.status IN (:statuses))
              AND (:methodCode IS NULL OR pm.method_code = :methodCode)
            """;

    public List<Map<String, Object>> listOrders(List<String> statuses, String methodCode,
                                                 String countryCode, int pageSize, int offset) {
        var params = new MapSqlParameterSource()
                .addValue("statuses", statuses.isEmpty() ? List.of("__none__") : statuses)
                .addValue("statusesEmpty", statuses.isEmpty())
                .addValue("methodCode", methodCode, Types.VARCHAR)
                .addValue("countryCode", countryCode, Types.VARCHAR)
                .addValue("pageSize", pageSize)
                .addValue("offset", offset);
        return jdbc.queryForList(LIST_ORDERS_SQL, params);
    }

    public long countOrders(List<String> statuses, String methodCode) {
        var params = new MapSqlParameterSource()
                .addValue("statuses", statuses.isEmpty() ? List.of("__none__") : statuses)
                .addValue("statusesEmpty", statuses.isEmpty())
                .addValue("methodCode", methodCode, Types.VARCHAR);
        Long count = jdbc.queryForObject(COUNT_ORDERS_SQL, params, Long.class);
        return count != null ? count : 0;
    }

    // ── Admin: subscription products ─────────────────────────────────────────

    private static final String LIST_SUBSCRIPTION_PRODUCTS_SQL = """
            SELECT sp.id, sp.product_code,
                   spl.plan_code, spl.name AS plan_name,
                   sp.billing_interval_unit, sp.billing_interval_count,
                   sp.auto_renew_supported, sp.is_active
            FROM subscription_products sp
            JOIN subscription_plans spl ON spl.id = sp.plan_id
            ORDER BY spl.plan_code, sp.billing_interval_count
            """;

    public List<Map<String, Object>> listSubscriptionProducts() {
        return jdbc.queryForList(LIST_SUBSCRIPTION_PRODUCTS_SQL, Map.of());
    }

    // ── Receipt proof for admin ─────────────────────────────────────────────

    private static final String FIND_RECEIPT_PROOF_SQL = """
            SELECT receipt_storage_bucket, receipt_storage_path
            FROM payment_proofs
            WHERE payment_order_id = :orderId
              AND proof_type = 'RECEIPT_UPLOAD'
              AND receipt_storage_bucket IS NOT NULL
            ORDER BY submitted_at DESC
            LIMIT 1
            """;

    public record ReceiptInfo(String bucket, String path) {}

    public Optional<ReceiptInfo> findReceiptProof(UUID orderId) {
        return jdbc.query(FIND_RECEIPT_PROOF_SQL, Map.of("orderId", orderId), (rs, rowNum) ->
                new ReceiptInfo(rs.getString("receipt_storage_bucket"), rs.getString("receipt_storage_path"))
        ).stream().findFirst();
    }

    // ── Billing customer ────────────────────────────────────────────────────

    private static final String FIND_USER_BY_EXTERNAL_CUSTOMER_SQL = """
            SELECT user_id FROM billing_customers
            WHERE provider = :provider AND external_customer_id = :externalId
            """;

    private static final String UPSERT_BILLING_CUSTOMER_SQL = """
            INSERT INTO billing_customers (user_id, provider, external_customer_id)
            VALUES (:userId, :provider, :externalId)
            ON CONFLICT (provider, external_customer_id) DO NOTHING
            """;

    public Optional<UUID> findUserByExternalCustomer(String provider, String externalId) {
        return jdbc.query(FIND_USER_BY_EXTERNAL_CUSTOMER_SQL,
                Map.of("provider", provider, "externalId", externalId),
                (rs, rowNum) -> rs.getObject("user_id", UUID.class))
                .stream().findFirst();
    }

    public void upsertBillingCustomer(UUID userId, String provider, String externalId) {
        jdbc.update(UPSERT_BILLING_CUSTOMER_SQL, Map.of(
                "userId", userId, "provider", provider, "externalId", externalId));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private OrderRow mapOrderRow(ResultSet rs, int rowNum) throws SQLException {
        return new OrderRow(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("payment_offer_id", UUID.class),
                rs.getObject("payment_method_id", UUID.class),
                rs.getString("order_reference"),
                rs.getString("status"),
                rs.getString("status_reason"),
                rs.getInt("expected_amount_minor_units"),
                rs.getString("expected_currency"),
                rs.getString("payment_channel"),
                rs.getString("payment_method"),
                rs.getString("payment_method_code"),
                rs.getString("payment_method_display_name"),
                rs.getString("provider_checkout_url"),
                rs.getString("provider_order_reference"),
                toInstant(rs, "expires_at"),
                toInstant(rs, "created_at"),
                toInstant(rs, "updated_at"),
                rs.getString("manual_payment_reference"),
                rs.getString("manual_payment_reference_normalized"),
                rs.getString("provider_verification_request_id"),
                rs.getObject("verification_count") != null ? rs.getInt("verification_count") : 0
        );
    }

    private static Instant toInstant(ResultSet rs, String col) throws SQLException {
        var ts = rs.getTimestamp(col);
        return ts != null ? ts.toInstant() : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonb(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseJsonbArray(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            return null;
        }
    }
}
