package com.qaliye.backend.billing.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AdminPaymentConfigRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AdminPaymentConfigRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // =========================================================================
    // Row records
    // =========================================================================

    public record SubscriptionPlanRow(
            UUID id, String name, String planCode, String countryCode,
            String planKind, Long priceMinorUnits, String currency,
            String billingInterval, String features, Boolean isActive
    ) {}

    public record SubscriptionProductRow(
            UUID id, UUID planId, String productCode,
            String billingIntervalUnit, Integer billingIntervalCount,
            Boolean autoRenewSupported, Long includedCredits, Boolean isActive
    ) {}

    public record ConsumableProductRow(
            UUID id, String productCode, String name, String entitlementType,
            Long quantityGranted, Integer expiresAfterDays, Boolean isActive
    ) {}

    public record PaymentOfferRow(
            UUID id, UUID subscriptionProductId, UUID consumableProductId,
            String countryCode, String platform, String currency,
            Integer priceMinorUnits, String externalProductId,
            String revenuecatOfferingId, String revenuecatPackageId,
            Boolean autoRenew, Boolean isActive
    ) {}

    public record PaymentMethodRow(
            UUID id, String countryCode, String platform, String methodCode,
            String displayName, String paymentChannel, String paymentMethod,
            String paymentInstructions, Boolean isActive, Short displayOrder,
            String metadata, String verificationParams, String logoUrl
    ) {}

    public record PlanLimitCostRow(
            UUID id, UUID subscriptionPlanId, UUID featureActionId,
            Long memberCreditCost, Long actualCreditCost, Integer limitValue,
            String periodType, Boolean applyCreditAfterLimit
    ) {}

    public record FeatureActionRow(
            UUID id, String code, String name, String type
    ) {}

    public record CountrySettingsRow(
            UUID id, String countryCode, Boolean subscriptionEnabled,
            Boolean creditsEnabled, Boolean identityVerificationRequired
    ) {}

    // =========================================================================
    // subscription_plans
    // =========================================================================

    private static final String SELECT_PLANS = """
            SELECT id, name, plan_code, country_code, plan_kind,
                   price_minor_units, currency, billing_interval,
                   features::text AS features, is_active
            FROM subscription_plans
            ORDER BY plan_kind, plan_code, country_code
            """;

    public List<SubscriptionPlanRow> listPlans() {
        return jdbc.query(SELECT_PLANS, Map.of(), (rs, rn) -> new SubscriptionPlanRow(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("plan_code"),
                rs.getString("country_code"),
                rs.getString("plan_kind"),
                rs.getObject("price_minor_units") != null ? rs.getLong("price_minor_units") : null,
                rs.getString("currency"),
                rs.getString("billing_interval"),
                rs.getString("features"),
                rs.getBoolean("is_active")
        ));
    }

    private static final String FIND_PLAN_BY_ID = """
            SELECT id, name, plan_code, country_code, plan_kind,
                   price_minor_units, currency, billing_interval,
                   features::text AS features, is_active
            FROM subscription_plans WHERE id = :id
            """;

    public Optional<SubscriptionPlanRow> findPlanById(UUID id) {
        return jdbc.query(FIND_PLAN_BY_ID, Map.of("id", id), (rs, rn) -> new SubscriptionPlanRow(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("plan_code"),
                rs.getString("country_code"),
                rs.getString("plan_kind"),
                rs.getObject("price_minor_units") != null ? rs.getLong("price_minor_units") : null,
                rs.getString("currency"),
                rs.getString("billing_interval"),
                rs.getString("features"),
                rs.getBoolean("is_active")
        )).stream().findFirst();
    }

    private static final String INSERT_PLAN = """
            INSERT INTO subscription_plans
                (name, plan_code, country_code, plan_kind, price_minor_units, currency,
                 billing_interval, features, is_active)
            VALUES
                (:name, :planCode, :countryCode, :planKind, :priceMinorUnits, :currency,
                 :billingInterval, :features::jsonb, :isActive)
            RETURNING id
            """;

    public UUID createPlan(String name, String planCode, String countryCode, String planKind,
                           Long priceMinorUnits, String currency, String billingInterval,
                           String features, Boolean isActive) {
        var params = new MapSqlParameterSource()
                .addValue("name", name)
                .addValue("planCode", planCode)
                .addValue("countryCode", countryCode)
                .addValue("planKind", planKind)
                .addValue("priceMinorUnits", priceMinorUnits)
                .addValue("currency", currency)
                .addValue("billingInterval", billingInterval)
                .addValue("features", features != null ? features : "{}")
                .addValue("isActive", isActive != null ? isActive : true);
        return jdbc.queryForObject(INSERT_PLAN, params, (rs, rn) -> rs.getObject("id", UUID.class));
    }

    private static final String UPDATE_PLAN = """
            UPDATE subscription_plans SET
                name = COALESCE(:name, name),
                plan_code = COALESCE(:planCode, plan_code),
                country_code = COALESCE(:countryCode, country_code),
                plan_kind = COALESCE(:planKind, plan_kind),
                price_minor_units = COALESCE(:priceMinorUnits, price_minor_units),
                currency = COALESCE(:currency, currency),
                billing_interval = COALESCE(:billingInterval, billing_interval),
                features = CASE WHEN :features IS NULL THEN features ELSE :features::jsonb END,
                is_active = COALESCE(:isActive, is_active),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """;

    public int updatePlan(UUID id, String name, String planCode, String countryCode, String planKind,
                          Long priceMinorUnits, String currency, String billingInterval,
                          String features, Boolean isActive) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("name", name)
                .addValue("planCode", planCode)
                .addValue("countryCode", countryCode)
                .addValue("planKind", planKind)
                .addValue("priceMinorUnits", priceMinorUnits)
                .addValue("currency", currency)
                .addValue("billingInterval", billingInterval)
                .addValue("features", features)
                .addValue("isActive", isActive);
        return jdbc.update(UPDATE_PLAN, params);
    }

    private static final String DELETE_PLAN = """
            UPDATE subscription_plans SET is_active = FALSE, updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """;

    public int deactivatePlan(UUID id) {
        return jdbc.update(DELETE_PLAN, Map.of("id", id));
    }

    // =========================================================================
    // subscription_products
    // =========================================================================

    private static final String SELECT_SUB_PRODUCTS = """
            SELECT id, plan_id, product_code, billing_interval_unit, billing_interval_count,
                   auto_renew_supported, included_credits, is_active
            FROM subscription_products
            ORDER BY product_code
            """;

    public List<SubscriptionProductRow> listSubscriptionProducts() {
        return jdbc.query(SELECT_SUB_PRODUCTS, Map.of(), (rs, rn) -> new SubscriptionProductRow(
                rs.getObject("id", UUID.class),
                rs.getObject("plan_id", UUID.class),
                rs.getString("product_code"),
                rs.getString("billing_interval_unit"),
                rs.getInt("billing_interval_count"),
                rs.getBoolean("auto_renew_supported"),
                rs.getLong("included_credits"),
                rs.getBoolean("is_active")
        ));
    }

    private static final String FIND_SUB_PRODUCT_BY_ID = """
            SELECT id, plan_id, product_code, billing_interval_unit, billing_interval_count,
                   auto_renew_supported, included_credits, is_active
            FROM subscription_products WHERE id = :id
            """;

    public Optional<SubscriptionProductRow> findSubscriptionProductById(UUID id) {
        return jdbc.query(FIND_SUB_PRODUCT_BY_ID, Map.of("id", id), (rs, rn) -> new SubscriptionProductRow(
                rs.getObject("id", UUID.class),
                rs.getObject("plan_id", UUID.class),
                rs.getString("product_code"),
                rs.getString("billing_interval_unit"),
                rs.getInt("billing_interval_count"),
                rs.getBoolean("auto_renew_supported"),
                rs.getLong("included_credits"),
                rs.getBoolean("is_active")
        )).stream().findFirst();
    }

    private static final String INSERT_SUB_PRODUCT = """
            INSERT INTO subscription_products
                (plan_id, product_code, billing_interval_unit, billing_interval_count,
                 auto_renew_supported, included_credits, is_active)
            VALUES
                (:planId, :productCode, :billingIntervalUnit, :billingIntervalCount,
                 :autoRenewSupported, :includedCredits, :isActive)
            RETURNING id
            """;

    public UUID createSubscriptionProduct(UUID planId, String productCode, String billingIntervalUnit,
                                           int billingIntervalCount, Boolean autoRenewSupported,
                                           Long includedCredits, Boolean isActive) {
        var params = new MapSqlParameterSource()
                .addValue("planId", planId)
                .addValue("productCode", productCode)
                .addValue("billingIntervalUnit", billingIntervalUnit)
                .addValue("billingIntervalCount", billingIntervalCount)
                .addValue("autoRenewSupported", autoRenewSupported != null ? autoRenewSupported : true)
                .addValue("includedCredits", includedCredits != null ? includedCredits : 0L)
                .addValue("isActive", isActive != null ? isActive : true);
        return jdbc.queryForObject(INSERT_SUB_PRODUCT, params, (rs, rn) -> rs.getObject("id", UUID.class));
    }

    private static final String UPDATE_SUB_PRODUCT = """
            UPDATE subscription_products SET
                plan_id = COALESCE(:planId, plan_id),
                product_code = COALESCE(:productCode, product_code),
                billing_interval_unit = COALESCE(:billingIntervalUnit, billing_interval_unit),
                billing_interval_count = COALESCE(:billingIntervalCount, billing_interval_count),
                auto_renew_supported = COALESCE(:autoRenewSupported, auto_renew_supported),
                included_credits = COALESCE(:includedCredits, included_credits),
                is_active = COALESCE(:isActive, is_active),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """;

    public int updateSubscriptionProduct(UUID id, UUID planId, String productCode, String billingIntervalUnit,
                                          Integer billingIntervalCount, Boolean autoRenewSupported,
                                          Long includedCredits, Boolean isActive) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("planId", planId)
                .addValue("productCode", productCode)
                .addValue("billingIntervalUnit", billingIntervalUnit)
                .addValue("billingIntervalCount", billingIntervalCount)
                .addValue("autoRenewSupported", autoRenewSupported)
                .addValue("includedCredits", includedCredits)
                .addValue("isActive", isActive);
        return jdbc.update(UPDATE_SUB_PRODUCT, params);
    }

    private static final String DEACTIVATE_SUB_PRODUCT = """
            UPDATE subscription_products SET is_active = FALSE, updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """;

    public int deactivateSubscriptionProduct(UUID id) {
        return jdbc.update(DEACTIVATE_SUB_PRODUCT, Map.of("id", id));
    }

    // =========================================================================
    // consumable_products
    // =========================================================================

    private static final String SELECT_CONSUMABLES = """
            SELECT id, product_code, name, entitlement_type, quantity_granted,
                   expires_after_days, is_active
            FROM consumable_products
            ORDER BY product_code
            """;

    public List<ConsumableProductRow> listConsumableProducts() {
        return jdbc.query(SELECT_CONSUMABLES, Map.of(), (rs, rn) -> new ConsumableProductRow(
                rs.getObject("id", UUID.class),
                rs.getString("product_code"),
                rs.getString("name"),
                rs.getString("entitlement_type"),
                rs.getLong("quantity_granted"),
                rs.getObject("expires_after_days") != null ? rs.getInt("expires_after_days") : null,
                rs.getBoolean("is_active")
        ));
    }

    private static final String FIND_CONSUMABLE_BY_ID = """
            SELECT id, product_code, name, entitlement_type, quantity_granted,
                   expires_after_days, is_active
            FROM consumable_products WHERE id = :id
            """;

    public Optional<ConsumableProductRow> findConsumableProductById(UUID id) {
        return jdbc.query(FIND_CONSUMABLE_BY_ID, Map.of("id", id), (rs, rn) -> new ConsumableProductRow(
                rs.getObject("id", UUID.class),
                rs.getString("product_code"),
                rs.getString("name"),
                rs.getString("entitlement_type"),
                rs.getLong("quantity_granted"),
                rs.getObject("expires_after_days") != null ? rs.getInt("expires_after_days") : null,
                rs.getBoolean("is_active")
        )).stream().findFirst();
    }

    private static final String INSERT_CONSUMABLE = """
            INSERT INTO consumable_products
                (product_code, name, entitlement_type, quantity_granted, expires_after_days, is_active)
            VALUES
                (:productCode, :name, :entitlementType, :quantityGranted, :expiresAfterDays, :isActive)
            RETURNING id
            """;

    public UUID createConsumableProduct(String productCode, String name, String entitlementType,
                                         long quantityGranted, Integer expiresAfterDays, Boolean isActive) {
        var params = new MapSqlParameterSource()
                .addValue("productCode", productCode)
                .addValue("name", name)
                .addValue("entitlementType", entitlementType)
                .addValue("quantityGranted", quantityGranted)
                .addValue("expiresAfterDays", expiresAfterDays)
                .addValue("isActive", isActive != null ? isActive : true);
        return jdbc.queryForObject(INSERT_CONSUMABLE, params, (rs, rn) -> rs.getObject("id", UUID.class));
    }

    private static final String UPDATE_CONSUMABLE = """
            UPDATE consumable_products SET
                product_code = COALESCE(:productCode, product_code),
                name = COALESCE(:name, name),
                entitlement_type = COALESCE(:entitlementType, entitlement_type),
                quantity_granted = COALESCE(:quantityGranted, quantity_granted),
                expires_after_days = COALESCE(:expiresAfterDays, expires_after_days),
                is_active = COALESCE(:isActive, is_active),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """;

    public int updateConsumableProduct(UUID id, String productCode, String name, String entitlementType,
                                        Long quantityGranted, Integer expiresAfterDays, Boolean isActive) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("productCode", productCode)
                .addValue("name", name)
                .addValue("entitlementType", entitlementType)
                .addValue("quantityGranted", quantityGranted)
                .addValue("expiresAfterDays", expiresAfterDays)
                .addValue("isActive", isActive);
        return jdbc.update(UPDATE_CONSUMABLE, params);
    }

    private static final String DEACTIVATE_CONSUMABLE = """
            UPDATE consumable_products SET is_active = FALSE, updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """;

    public int deactivateConsumableProduct(UUID id) {
        return jdbc.update(DEACTIVATE_CONSUMABLE, Map.of("id", id));
    }

    // =========================================================================
    // payment_offers
    // =========================================================================

    private static final String SELECT_OFFERS = """
            SELECT id, subscription_product_id, consumable_product_id, country_code, platform,
                   currency, price_minor_units, external_product_id, revenuecat_offering_id,
                   revenuecat_package_id, auto_renew, is_active
            FROM payment_offers
            ORDER BY country_code, platform, currency, price_minor_units
            """;

    public List<PaymentOfferRow> listPaymentOffers() {
        return jdbc.query(SELECT_OFFERS, Map.of(), (rs, rn) -> new PaymentOfferRow(
                rs.getObject("id", UUID.class),
                rs.getObject("subscription_product_id", UUID.class),
                rs.getObject("consumable_product_id", UUID.class),
                rs.getString("country_code"),
                rs.getString("platform"),
                rs.getString("currency"),
                rs.getInt("price_minor_units"),
                rs.getString("external_product_id"),
                rs.getString("revenuecat_offering_id"),
                rs.getString("revenuecat_package_id"),
                rs.getBoolean("auto_renew"),
                rs.getBoolean("is_active")
        ));
    }

    private static final String FIND_OFFER_BY_ID = """
            SELECT id, subscription_product_id, consumable_product_id, country_code, platform,
                   currency, price_minor_units, external_product_id, revenuecat_offering_id,
                   revenuecat_package_id, auto_renew, is_active
            FROM payment_offers WHERE id = :id
            """;

    public Optional<PaymentOfferRow> findPaymentOfferById(UUID id) {
        return jdbc.query(FIND_OFFER_BY_ID, Map.of("id", id), (rs, rn) -> new PaymentOfferRow(
                rs.getObject("id", UUID.class),
                rs.getObject("subscription_product_id", UUID.class),
                rs.getObject("consumable_product_id", UUID.class),
                rs.getString("country_code"),
                rs.getString("platform"),
                rs.getString("currency"),
                rs.getInt("price_minor_units"),
                rs.getString("external_product_id"),
                rs.getString("revenuecat_offering_id"),
                rs.getString("revenuecat_package_id"),
                rs.getBoolean("auto_renew"),
                rs.getBoolean("is_active")
        )).stream().findFirst();
    }

    private static final String INSERT_OFFER = """
            INSERT INTO payment_offers
                (subscription_product_id, consumable_product_id, country_code, platform,
                 currency, price_minor_units, external_product_id, revenuecat_offering_id,
                 revenuecat_package_id, auto_renew, is_active)
            VALUES
                (:subscriptionProductId, :consumableProductId, :countryCode, :platform,
                 :currency, :priceMinorUnits, :externalProductId, :revenuecatOfferingId,
                 :revenuecatPackageId, :autoRenew, :isActive)
            RETURNING id
            """;

    public UUID createPaymentOffer(UUID subscriptionProductId, UUID consumableProductId, String countryCode,
                                    String platform, String currency, int priceMinorUnits,
                                    String externalProductId, String revenuecatOfferingId,
                                    String revenuecatPackageId, Boolean autoRenew, Boolean isActive) {
        var params = new MapSqlParameterSource()
                .addValue("subscriptionProductId", subscriptionProductId)
                .addValue("consumableProductId", consumableProductId)
                .addValue("countryCode", countryCode)
                .addValue("platform", platform)
                .addValue("currency", currency)
                .addValue("priceMinorUnits", priceMinorUnits)
                .addValue("externalProductId", externalProductId)
                .addValue("revenuecatOfferingId", revenuecatOfferingId)
                .addValue("revenuecatPackageId", revenuecatPackageId)
                .addValue("autoRenew", autoRenew != null ? autoRenew : false)
                .addValue("isActive", isActive != null ? isActive : true);
        return jdbc.queryForObject(INSERT_OFFER, params, (rs, rn) -> rs.getObject("id", UUID.class));
    }

    private static final String UPDATE_OFFER = """
            UPDATE payment_offers SET
                subscription_product_id = COALESCE(:subscriptionProductId, subscription_product_id),
                consumable_product_id = COALESCE(:consumableProductId, consumable_product_id),
                country_code = COALESCE(:countryCode, country_code),
                platform = COALESCE(:platform, platform),
                currency = COALESCE(:currency, currency),
                price_minor_units = COALESCE(:priceMinorUnits, price_minor_units),
                external_product_id = COALESCE(:externalProductId, external_product_id),
                revenuecat_offering_id = COALESCE(:revenuecatOfferingId, revenuecat_offering_id),
                revenuecat_package_id = COALESCE(:revenuecatPackageId, revenuecat_package_id),
                auto_renew = COALESCE(:autoRenew, auto_renew),
                is_active = COALESCE(:isActive, is_active),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """;

    public int updatePaymentOffer(UUID id, UUID subscriptionProductId, UUID consumableProductId,
                                   String countryCode, String platform, String currency,
                                   Integer priceMinorUnits, String externalProductId,
                                   String revenuecatOfferingId, String revenuecatPackageId,
                                   Boolean autoRenew, Boolean isActive) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("subscriptionProductId", subscriptionProductId)
                .addValue("consumableProductId", consumableProductId)
                .addValue("countryCode", countryCode)
                .addValue("platform", platform)
                .addValue("currency", currency)
                .addValue("priceMinorUnits", priceMinorUnits)
                .addValue("externalProductId", externalProductId)
                .addValue("revenuecatOfferingId", revenuecatOfferingId)
                .addValue("revenuecatPackageId", revenuecatPackageId)
                .addValue("autoRenew", autoRenew)
                .addValue("isActive", isActive);
        return jdbc.update(UPDATE_OFFER, params);
    }

    private static final String DEACTIVATE_OFFER = """
            UPDATE payment_offers SET is_active = FALSE, updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """;

    public int deactivatePaymentOffer(UUID id) {
        return jdbc.update(DEACTIVATE_OFFER, Map.of("id", id));
    }

    // =========================================================================
    // payment_methods
    // =========================================================================

    private static final String SELECT_METHODS = """
            SELECT id, country_code, platform, method_code, display_name,
                   payment_channel, payment_method, payment_instructions,
                   is_active, display_order, metadata::text AS metadata,
                   verification_params::text AS verification_params, logo_url
            FROM payment_methods
            ORDER BY country_code, platform, display_order, method_code
            """;

    public List<PaymentMethodRow> listPaymentMethods() {
        return jdbc.query(SELECT_METHODS, Map.of(), (rs, rn) -> new PaymentMethodRow(
                rs.getObject("id", UUID.class),
                rs.getString("country_code"),
                rs.getString("platform"),
                rs.getString("method_code"),
                rs.getString("display_name"),
                rs.getString("payment_channel"),
                rs.getString("payment_method"),
                rs.getString("payment_instructions"),
                rs.getBoolean("is_active"),
                rs.getShort("display_order"),
                rs.getString("metadata"),
                rs.getString("verification_params"),
                rs.getString("logo_url")
        ));
    }

    private static final String FIND_METHOD_BY_ID = """
            SELECT id, country_code, platform, method_code, display_name,
                   payment_channel, payment_method, payment_instructions,
                   is_active, display_order, metadata::text AS metadata,
                   verification_params::text AS verification_params, logo_url
            FROM payment_methods WHERE id = :id
            """;

    public Optional<PaymentMethodRow> findPaymentMethodById(UUID id) {
        return jdbc.query(FIND_METHOD_BY_ID, Map.of("id", id), (rs, rn) -> new PaymentMethodRow(
                rs.getObject("id", UUID.class),
                rs.getString("country_code"),
                rs.getString("platform"),
                rs.getString("method_code"),
                rs.getString("display_name"),
                rs.getString("payment_channel"),
                rs.getString("payment_method"),
                rs.getString("payment_instructions"),
                rs.getBoolean("is_active"),
                rs.getShort("display_order"),
                rs.getString("metadata"),
                rs.getString("verification_params"),
                rs.getString("logo_url")
        )).stream().findFirst();
    }

    private static final String INSERT_METHOD = """
            INSERT INTO payment_methods
                (country_code, platform, method_code, display_name, payment_channel,
                 payment_method, payment_instructions, is_active, display_order,
                 metadata, verification_params, logo_url)
            VALUES
                (:countryCode, :platform, :methodCode, :displayName, :paymentChannel,
                 :paymentMethod, :paymentInstructions, :isActive, :displayOrder,
                 :metadata::jsonb, :verificationParams::jsonb, :logoUrl)
            RETURNING id
            """;

    public UUID createPaymentMethod(String countryCode, String platform, String methodCode,
                                     String displayName, String paymentChannel, String paymentMethod,
                                     String paymentInstructions, Boolean isActive, Short displayOrder,
                                     String metadata, String verificationParams, String logoUrl) {
        var params = new MapSqlParameterSource()
                .addValue("countryCode", countryCode)
                .addValue("platform", platform)
                .addValue("methodCode", methodCode)
                .addValue("displayName", displayName)
                .addValue("paymentChannel", paymentChannel)
                .addValue("paymentMethod", paymentMethod)
                .addValue("paymentInstructions", paymentInstructions)
                .addValue("isActive", isActive != null ? isActive : true)
                .addValue("displayOrder", displayOrder != null ? displayOrder : 0)
                .addValue("metadata", metadata != null ? metadata : "{}")
                .addValue("verificationParams", verificationParams)
                .addValue("logoUrl", logoUrl);
        return jdbc.queryForObject(INSERT_METHOD, params, (rs, rn) -> rs.getObject("id", UUID.class));
    }

    private static final String UPDATE_METHOD = """
            UPDATE payment_methods SET
                country_code = COALESCE(:countryCode, country_code),
                platform = COALESCE(:platform, platform),
                method_code = COALESCE(:methodCode, method_code),
                display_name = COALESCE(:displayName, display_name),
                payment_channel = COALESCE(:paymentChannel, payment_channel),
                payment_method = COALESCE(:paymentMethod, payment_method),
                payment_instructions = COALESCE(:paymentInstructions, payment_instructions),
                is_active = COALESCE(:isActive, is_active),
                display_order = COALESCE(:displayOrder, display_order),
                metadata = CASE WHEN :metadata IS NULL THEN metadata ELSE :metadata::jsonb END,
                verification_params = CASE WHEN :verificationParams IS NULL THEN verification_params ELSE :verificationParams::jsonb END,
                logo_url = COALESCE(:logoUrl, logo_url),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """;

    public int updatePaymentMethod(UUID id, String countryCode, String platform, String methodCode,
                                    String displayName, String paymentChannel, String paymentMethod,
                                    String paymentInstructions, Boolean isActive, Short displayOrder,
                                    String metadata, String verificationParams, String logoUrl) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("countryCode", countryCode)
                .addValue("platform", platform)
                .addValue("methodCode", methodCode)
                .addValue("displayName", displayName)
                .addValue("paymentChannel", paymentChannel)
                .addValue("paymentMethod", paymentMethod)
                .addValue("paymentInstructions", paymentInstructions)
                .addValue("isActive", isActive)
                .addValue("displayOrder", displayOrder)
                .addValue("metadata", metadata)
                .addValue("verificationParams", verificationParams)
                .addValue("logoUrl", logoUrl);
        return jdbc.update(UPDATE_METHOD, params);
    }

    private static final String DEACTIVATE_METHOD = """
            UPDATE payment_methods SET is_active = FALSE, updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """;

    public int deactivatePaymentMethod(UUID id) {
        return jdbc.update(DEACTIVATE_METHOD, Map.of("id", id));
    }

    // =========================================================================
    // subscription_plan_limit_and_cost
    // =========================================================================

    private static final String SELECT_LIMITS = """
            SELECT id, subscription_plan_id, feature_action_id, member_credit_cost,
                   actual_credit_cost, limit_value, period_type, apply_credit_after_limit
            FROM subscription_plan_limit_and_cost
            ORDER BY subscription_plan_id, feature_action_id
            """;

    public List<PlanLimitCostRow> listPlanLimitCosts() {
        return jdbc.query(SELECT_LIMITS, Map.of(), (rs, rn) -> new PlanLimitCostRow(
                rs.getObject("id", UUID.class),
                rs.getObject("subscription_plan_id", UUID.class),
                rs.getObject("feature_action_id", UUID.class),
                rs.getLong("member_credit_cost"),
                rs.getLong("actual_credit_cost"),
                rs.getObject("limit_value") != null ? rs.getInt("limit_value") : null,
                rs.getString("period_type"),
                rs.getBoolean("apply_credit_after_limit")
        ));
    }

    private static final String FIND_LIMIT_BY_ID = """
            SELECT id, subscription_plan_id, feature_action_id, member_credit_cost,
                   actual_credit_cost, limit_value, period_type, apply_credit_after_limit
            FROM subscription_plan_limit_and_cost WHERE id = :id
            """;

    public Optional<PlanLimitCostRow> findPlanLimitCostById(UUID id) {
        return jdbc.query(FIND_LIMIT_BY_ID, Map.of("id", id), (rs, rn) -> new PlanLimitCostRow(
                rs.getObject("id", UUID.class),
                rs.getObject("subscription_plan_id", UUID.class),
                rs.getObject("feature_action_id", UUID.class),
                rs.getLong("member_credit_cost"),
                rs.getLong("actual_credit_cost"),
                rs.getObject("limit_value") != null ? rs.getInt("limit_value") : null,
                rs.getString("period_type"),
                rs.getBoolean("apply_credit_after_limit")
        )).stream().findFirst();
    }

    private static final String INSERT_LIMIT = """
            INSERT INTO subscription_plan_limit_and_cost
                (subscription_plan_id, feature_action_id, member_credit_cost, actual_credit_cost,
                 limit_value, period_type, apply_credit_after_limit)
            VALUES
                (:subscriptionPlanId, :featureActionId, :memberCreditCost, :actualCreditCost,
                 :limitValue, :periodType, :applyCreditAfterLimit)
            RETURNING id
            """;

    public UUID createPlanLimitCost(UUID subscriptionPlanId, UUID featureActionId, long memberCreditCost,
                                     long actualCreditCost, Integer limitValue, String periodType,
                                     Boolean applyCreditAfterLimit) {
        var params = new MapSqlParameterSource()
                .addValue("subscriptionPlanId", subscriptionPlanId)
                .addValue("featureActionId", featureActionId)
                .addValue("memberCreditCost", memberCreditCost)
                .addValue("actualCreditCost", actualCreditCost)
                .addValue("limitValue", limitValue)
                .addValue("periodType", periodType != null ? periodType : "DAY")
                .addValue("applyCreditAfterLimit", applyCreditAfterLimit != null ? applyCreditAfterLimit : false);
        return jdbc.queryForObject(INSERT_LIMIT, params, (rs, rn) -> rs.getObject("id", UUID.class));
    }

    private static final String UPDATE_LIMIT = """
            UPDATE subscription_plan_limit_and_cost SET
                subscription_plan_id = COALESCE(:subscriptionPlanId, subscription_plan_id),
                feature_action_id = COALESCE(:featureActionId, feature_action_id),
                member_credit_cost = COALESCE(:memberCreditCost, member_credit_cost),
                actual_credit_cost = COALESCE(:actualCreditCost, actual_credit_cost),
                limit_value = COALESCE(:limitValue, limit_value),
                period_type = COALESCE(:periodType, period_type),
                apply_credit_after_limit = COALESCE(:applyCreditAfterLimit, apply_credit_after_limit),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """;

    public int updatePlanLimitCost(UUID id, UUID subscriptionPlanId, UUID featureActionId,
                                    Long memberCreditCost, Long actualCreditCost, Integer limitValue,
                                    String periodType, Boolean applyCreditAfterLimit) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("subscriptionPlanId", subscriptionPlanId)
                .addValue("featureActionId", featureActionId)
                .addValue("memberCreditCost", memberCreditCost)
                .addValue("actualCreditCost", actualCreditCost)
                .addValue("limitValue", limitValue)
                .addValue("periodType", periodType)
                .addValue("applyCreditAfterLimit", applyCreditAfterLimit);
        return jdbc.update(UPDATE_LIMIT, params);
    }

    private static final String DELETE_LIMIT = """
            DELETE FROM subscription_plan_limit_and_cost WHERE id = :id
            """;

    public int deletePlanLimitCost(UUID id) {
        return jdbc.update(DELETE_LIMIT, Map.of("id", id));
    }

    // =========================================================================
    // feature_actions
    // =========================================================================

    private static final String SELECT_FEATURE_ACTIONS = """
            SELECT id, code, name, type FROM feature_actions ORDER BY code
            """;

    public List<FeatureActionRow> listFeatureActions() {
        return jdbc.query(SELECT_FEATURE_ACTIONS, Map.of(), (rs, rn) -> new FeatureActionRow(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("type")
        ));
    }

    private static final String FIND_FEATURE_ACTION_BY_ID = """
            SELECT id, code, name, type FROM feature_actions WHERE id = :id
            """;

    public Optional<FeatureActionRow> findFeatureActionById(UUID id) {
        return jdbc.query(FIND_FEATURE_ACTION_BY_ID, Map.of("id", id), (rs, rn) -> new FeatureActionRow(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("type")
        )).stream().findFirst();
    }

    private static final String INSERT_FEATURE_ACTION = """
            INSERT INTO feature_actions (code, name, type)
            VALUES (:code, :name, :type)
            RETURNING id
            """;

    public UUID createFeatureAction(String code, String name, String type) {
        var params = new MapSqlParameterSource()
                .addValue("code", code)
                .addValue("name", name)
                .addValue("type", type);
        return jdbc.queryForObject(INSERT_FEATURE_ACTION, params, (rs, rn) -> rs.getObject("id", UUID.class));
    }

    private static final String UPDATE_FEATURE_ACTION = """
            UPDATE feature_actions SET
                code = COALESCE(:code, code),
                name = COALESCE(:name, name),
                type = COALESCE(:type, type)
            WHERE id = :id
            """;

    public int updateFeatureAction(UUID id, String code, String name, String type) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("code", code)
                .addValue("name", name)
                .addValue("type", type);
        return jdbc.update(UPDATE_FEATURE_ACTION, params);
    }

    private static final String DELETE_FEATURE_ACTION = """
            DELETE FROM feature_actions WHERE id = :id
            """;

    public int deleteFeatureAction(UUID id) {
        return jdbc.update(DELETE_FEATURE_ACTION, Map.of("id", id));
    }

    // =========================================================================
    // country_settings
    // =========================================================================

    private static final String SELECT_COUNTRY_SETTINGS = """
            SELECT id, country_code, subscription_enabled, credits_enabled,
                   identity_verification_required
            FROM country_settings
            ORDER BY country_code
            """;

    public List<CountrySettingsRow> listCountrySettings() {
        return jdbc.query(SELECT_COUNTRY_SETTINGS, Map.of(), (rs, rn) -> new CountrySettingsRow(
                rs.getObject("id", UUID.class),
                rs.getString("country_code"),
                rs.getBoolean("subscription_enabled"),
                rs.getBoolean("credits_enabled"),
                rs.getBoolean("identity_verification_required")
        ));
    }

    private static final String FIND_COUNTRY_SETTING_BY_ID = """
            SELECT id, country_code, subscription_enabled, credits_enabled,
                   identity_verification_required
            FROM country_settings WHERE id = :id
            """;

    public Optional<CountrySettingsRow> findCountrySettingById(UUID id) {
        return jdbc.query(FIND_COUNTRY_SETTING_BY_ID, Map.of("id", id), (rs, rn) -> new CountrySettingsRow(
                rs.getObject("id", UUID.class),
                rs.getString("country_code"),
                rs.getBoolean("subscription_enabled"),
                rs.getBoolean("credits_enabled"),
                rs.getBoolean("identity_verification_required")
        )).stream().findFirst();
    }

    private static final String INSERT_COUNTRY_SETTING = """
            INSERT INTO country_settings
                (country_code, subscription_enabled, credits_enabled, identity_verification_required)
            VALUES
                (:countryCode, :subscriptionEnabled, :creditsEnabled, :identityVerificationRequired)
            RETURNING id
            """;

    public UUID createCountrySetting(String countryCode, Boolean subscriptionEnabled,
                                      Boolean creditsEnabled, Boolean identityVerificationRequired) {
        var params = new MapSqlParameterSource()
                .addValue("countryCode", countryCode)
                .addValue("subscriptionEnabled", subscriptionEnabled != null ? subscriptionEnabled : true)
                .addValue("creditsEnabled", creditsEnabled != null ? creditsEnabled : true)
                .addValue("identityVerificationRequired", identityVerificationRequired != null ? identityVerificationRequired : false);
        return jdbc.queryForObject(INSERT_COUNTRY_SETTING, params, (rs, rn) -> rs.getObject("id", UUID.class));
    }

    private static final String UPDATE_COUNTRY_SETTING = """
            UPDATE country_settings SET
                country_code = COALESCE(:countryCode, country_code),
                subscription_enabled = COALESCE(:subscriptionEnabled, subscription_enabled),
                credits_enabled = COALESCE(:creditsEnabled, credits_enabled),
                identity_verification_required = COALESCE(:identityVerificationRequired, identity_verification_required),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """;

    public int updateCountrySetting(UUID id, String countryCode, Boolean subscriptionEnabled,
                                     Boolean creditsEnabled, Boolean identityVerificationRequired) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("countryCode", countryCode)
                .addValue("subscriptionEnabled", subscriptionEnabled)
                .addValue("creditsEnabled", creditsEnabled)
                .addValue("identityVerificationRequired", identityVerificationRequired);
        return jdbc.update(UPDATE_COUNTRY_SETTING, params);
    }

    private static final String DELETE_COUNTRY_SETTING = """
            DELETE FROM country_settings WHERE id = :id
            """;

    public int deleteCountrySetting(UUID id) {
        return jdbc.update(DELETE_COUNTRY_SETTING, Map.of("id", id));
    }

    // =========================================================================
    // Admin role enforcement
    // =========================================================================

    private static final String CHECK_ADMIN = """
            SELECT role FROM app_users WHERE id = :userId
            """;

    public boolean isAdmin(UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(CHECK_ADMIN, Map.of("userId", userId));
        return !rows.isEmpty() && "ADMIN".equals(rows.get(0).get("role"));
    }
}
