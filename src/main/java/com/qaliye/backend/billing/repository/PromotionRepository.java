package com.qaliye.backend.billing.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PromotionRepository {

    private static final Logger log = LoggerFactory.getLogger(PromotionRepository.class);

    private final NamedParameterJdbcTemplate jdbc;

    public PromotionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Records ─────────────────────────────────────────────────────────────

    public record CampaignRow(
            UUID id, String campaignKey, String name, String description,
            String triggerType, String eligibilityType, String benefitType,
            String discountType, Long discountValue, String discountCurrency,
            UUID subscriptionProductId, String countryCode,
            Integer durationDays, Integer newUserWindowDays,
            Integer maxRedemptions, int maxRedemptionsPerUser,
            int reservedCount, int fulfilledCount,
            int priority, Instant startsAt, Instant endsAt,
            String status, String targetGender, UUID createdByUserId,
            Instant createdAt, Instant updatedAt
    ) {}

    public record RedemptionRow(
            UUID id, UUID campaignId, UUID userId, UUID subscriptionId,
            UUID paymentOfferId, UUID paymentOrderId,
            String status, String eligibilityCountry, String eligibilityGender,
            Long originalAmountMinor, Long discountAmountMinor, Long finalAmountMinor,
            String currency, Instant reservedAt, Instant fulfilledAt,
            Instant cancelledAt, Instant expiredAt, Instant failedAt,
            String failureCode, String failureReason,
            Instant createdAt, Instant updatedAt
    ) {}

    public record UserRedemptionRow(
            UUID id, UUID campaignId, String campaignKey, String campaignName,
            String benefitType, Integer durationDays,
            UUID userId, UUID subscriptionId, UUID paymentOrderId,
            String status,
            Long originalAmountMinor, Long discountAmountMinor, Long finalAmountMinor,
            String currency, Instant reservedAt, Instant fulfilledAt,
            Instant cancelledAt, Instant expiredAt, String failureCode,
            String subscriptionStatus, Instant subscriptionPeriodEnd
    ) {}

    // ── Campaign SQL ─────────────────────────────────────────────────────────

    private static final String CAMPAIGN_SELECT = """
            SELECT id, campaign_key, name, description,
                   trigger_type, eligibility_type, benefit_type,
                   discount_type, discount_value, discount_currency,
                   subscription_product_id, country_code,
                   duration_days, new_user_window_days,
                   max_redemptions, max_redemptions_per_user,
                   reserved_count, fulfilled_count,
                   priority, starts_at, ends_at, status,
                   target_gender, created_by_user_id,
                   created_at, updated_at
            FROM promotion_campaigns
            """;

    private static final String FIND_CAMPAIGN_BY_ID_SQL =
            CAMPAIGN_SELECT + "WHERE id = :id";

    private static final String FIND_CAMPAIGN_BY_KEY_SQL =
            CAMPAIGN_SELECT + "WHERE campaign_key = :key";

    private static final String FIND_ACTIVE_CAMPAIGNS_BY_TRIGGER_PRODUCT_SQL =
            CAMPAIGN_SELECT + """
            WHERE status = 'ACTIVE'
              AND trigger_type = :triggerType
              AND subscription_product_id = :productId
              AND starts_at <= :now
              AND (ends_at IS NULL OR ends_at > :now)
              AND country_code IN (:countryCodes)
            ORDER BY CASE WHEN country_code = :primaryCountry THEN 0 ELSE 1 END, priority DESC
            """;

    private static final String FIND_ACTIVE_CAMPAIGNS_BY_TRIGGER_SQL =
            CAMPAIGN_SELECT + """
            WHERE status = 'ACTIVE'
              AND trigger_type = :triggerType
              AND starts_at <= :now
              AND (ends_at IS NULL OR ends_at > :now)
              AND country_code IN (:countryCodes)
            ORDER BY CASE WHEN country_code = :primaryCountry THEN 0 ELSE 1 END, priority DESC
            """;

    private static final String FIND_ACTIVE_PURCHASE_CAMPAIGNS_SQL =
            CAMPAIGN_SELECT + """
            WHERE status = 'ACTIVE'
              AND trigger_type = 'PURCHASE'
              AND benefit_type = 'DISCOUNT'
              AND subscription_product_id = :productId
              AND starts_at <= :now
              AND (ends_at IS NULL OR ends_at > :now)
              AND country_code IN (:countryCodes)
            ORDER BY CASE WHEN country_code = :primaryCountry THEN 0 ELSE 1 END, priority DESC
            """;

    private static final String INSERT_CAMPAIGN_SQL = """
            INSERT INTO promotion_campaigns
                (campaign_key, name, description, trigger_type, eligibility_type, benefit_type,
                 discount_type, discount_value, discount_currency,
                 subscription_product_id, country_code,
                 duration_days, new_user_window_days,
                 max_redemptions, max_redemptions_per_user, priority,
                 starts_at, ends_at, status, target_gender, created_by_user_id)
            VALUES
                (:campaignKey, :name, :description, :triggerType, :eligibilityType, :benefitType,
                 :discountType, :discountValue, :discountCurrency,
                 :subscriptionProductId, :countryCode,
                 :durationDays, :newUserWindowDays,
                 :maxRedemptions, :maxRedemptionsPerUser, :priority,
                 :startsAt, :endsAt, 'DRAFT', :targetGender, :createdBy)
            RETURNING id
            """;

    private static final String UPDATE_CAMPAIGN_SQL = """
            UPDATE promotion_campaigns
            SET name = COALESCE(:name, name),
                description = COALESCE(:description, description),
                max_redemptions = :maxRedemptions,
                max_redemptions_per_user = COALESCE(:maxPerUser, max_redemptions_per_user),
                priority = COALESCE(:priority, priority),
                ends_at = :endsAt,
                target_gender = :targetGender,
                updated_at = NOW()
            WHERE id = :id
            """;

    private static final String UPDATE_CAMPAIGN_STATUS_SQL = """
            UPDATE promotion_campaigns
            SET status = :status, updated_at = NOW()
            WHERE id = :id
            """;

    private static final String LIST_CAMPAIGNS_SQL =
            CAMPAIGN_SELECT + """
            WHERE (:status IS NULL OR status = :status)
            ORDER BY created_at DESC
            LIMIT :limit OFFSET :offset
            """;

    private static final String COUNT_CAMPAIGNS_SQL = """
            SELECT COUNT(*) FROM promotion_campaigns
            WHERE (:status IS NULL OR status = :status)
            """;

    private static final String ATOMIC_RESERVE_SQL = """
            UPDATE promotion_campaigns
            SET reserved_count = reserved_count + 1, updated_at = NOW()
            WHERE id = :campaignId
              AND status = 'ACTIVE'
              AND starts_at <= NOW()
              AND (ends_at IS NULL OR ends_at > NOW())
              AND (max_redemptions IS NULL OR fulfilled_count + reserved_count < max_redemptions)
            """;

    private static final String ATOMIC_FULFILL_SQL = """
            UPDATE promotion_campaigns
            SET reserved_count = GREATEST(0, reserved_count - 1),
                fulfilled_count = fulfilled_count + 1,
                updated_at = NOW()
            WHERE id = :campaignId
            """;

    private static final String RELEASE_RESERVATION_SQL = """
            UPDATE promotion_campaigns
            SET reserved_count = GREATEST(0, reserved_count - 1), updated_at = NOW()
            WHERE id = :campaignId
            """;

    private static final String INCREMENT_FULFILLED_SQL = """
            UPDATE promotion_campaigns
            SET fulfilled_count = fulfilled_count + 1, updated_at = NOW()
            WHERE id = :campaignId
            """;

    private static final String COUNT_ACTIVE_REDEMPTIONS_FOR_USER_SQL = """
            SELECT COUNT(*) FROM promotion_redemptions
            WHERE campaign_id = :campaignId AND user_id = :userId
              AND status IN ('RESERVED', 'PROVIDER_PENDING', 'FULFILLED')
            """;

    // ── Redemption SQL ───────────────────────────────────────────────────────

    private static final String REDEMPTION_SELECT = """
            SELECT r.id, r.campaign_id, r.user_id, r.subscription_id,
                   r.payment_offer_id, r.payment_order_id,
                   r.status, r.eligibility_country, r.eligibility_gender,
                   r.original_amount_minor, r.discount_amount_minor, r.final_amount_minor,
                   r.currency, r.reserved_at, r.fulfilled_at,
                   r.cancelled_at, r.expired_at, r.failed_at,
                   r.failure_code, r.failure_reason,
                   r.created_at, r.updated_at
            FROM promotion_redemptions r
            """;

    private static final String INSERT_REDEMPTION_SQL = """
            INSERT INTO promotion_redemptions
                (campaign_id, user_id, payment_offer_id, payment_order_id, status,
                 eligibility_country, eligibility_gender,
                 original_amount_minor, discount_amount_minor,
                 final_amount_minor, currency)
            VALUES
                (:campaignId, :userId, :paymentOfferId, :paymentOrderId, :status,
                 :eligibilityCountry, :eligibilityGender,
                 :originalAmount, :discountAmount, :finalAmount, :currency)
            RETURNING id
            """;

    private static final String UPDATE_REDEMPTION_FULFILLED_SQL = """
            UPDATE promotion_redemptions
            SET status = 'FULFILLED', subscription_id = :subscriptionId,
                fulfilled_at = NOW(), updated_at = NOW()
            WHERE id = :id
            """;

    private static final String UPDATE_REDEMPTION_CANCELLED_SQL = """
            UPDATE promotion_redemptions
            SET status = 'CANCELLED', cancelled_at = NOW(), updated_at = NOW(),
                failure_code = :failureCode, failure_reason = :failureReason
            WHERE id = :id
            """;

    private static final String UPDATE_REDEMPTION_EXPIRED_SQL = """
            UPDATE promotion_redemptions
            SET status = 'EXPIRED', expired_at = NOW(), updated_at = NOW()
            WHERE id = :id
            """;

    private static final String FIND_RESERVED_REDEMPTION_BY_ORDER_SQL =
            REDEMPTION_SELECT + """
            WHERE r.payment_order_id = :orderId
              AND r.status IN ('RESERVED', 'PROVIDER_PENDING')
            LIMIT 1
            """;

    private static final String CANCEL_REDEMPTIONS_BY_ORDER_SQL = """
            UPDATE promotion_redemptions
            SET status = 'CANCELLED', cancelled_at = NOW(), updated_at = NOW(),
                failure_code = :failureCode
            WHERE payment_order_id = :orderId
              AND status IN ('RESERVED', 'PROVIDER_PENDING')
            RETURNING campaign_id
            """;

    private static final String EXPIRE_STALE_REDEMPTIONS_SQL = """
            WITH expired AS (
                UPDATE promotion_redemptions
                SET status = 'EXPIRED', expired_at = NOW(), updated_at = NOW()
                WHERE status IN ('RESERVED', 'PROVIDER_PENDING')
                  AND reserved_at < :cutoff
                RETURNING campaign_id
            )
            SELECT campaign_id, COUNT(*) AS cnt
            FROM expired
            GROUP BY campaign_id
            """;

    private static final String LIST_REDEMPTIONS_BY_CAMPAIGN_SQL =
            REDEMPTION_SELECT + """
            WHERE r.campaign_id = :campaignId
            ORDER BY r.reserved_at DESC
            LIMIT :limit OFFSET :offset
            """;

    private static final String LIST_REDEMPTIONS_BY_USER_SQL =
            """
            SELECT r.id, r.campaign_id, c.campaign_key, c.name AS campaign_name,
                   c.benefit_type, c.duration_days,
                   r.user_id, r.subscription_id, r.payment_order_id,
                   r.status,
                   r.original_amount_minor, r.discount_amount_minor, r.final_amount_minor,
                   r.currency, r.reserved_at, r.fulfilled_at,
                   r.cancelled_at, r.expired_at, r.failure_code,
                   us.status AS subscription_status, us.current_period_end AS subscription_period_end
            FROM promotion_redemptions r
            JOIN promotion_campaigns c ON c.id = r.campaign_id
            LEFT JOIN user_subscriptions us ON us.id = r.subscription_id
            WHERE r.user_id = :userId
            ORDER BY r.reserved_at DESC
            LIMIT :limit OFFSET :offset
            """;

    private static final String USER_CREATED_AT_SQL = """
            SELECT created_at FROM app_users WHERE id = :userId
            """;

    private static final String HAS_ANY_SUBSCRIPTION_SQL = """
            SELECT EXISTS (
                SELECT 1 FROM user_subscriptions
                WHERE user_id = :userId AND status NOT IN ('EXPIRED', 'REVOKED')
            )
            """;

    private static final String HAS_ACTIVE_SUBSCRIPTION_SQL = """
            SELECT EXISTS (
                SELECT 1 FROM user_subscriptions
                WHERE user_id = :userId
                  AND status IN ('ACTIVE', 'GRACE_PERIOD')
                  AND current_period_end > NOW()
            )
            """;

    private static final String FIND_PLAN_ID_FOR_PRODUCT_SQL = """
            SELECT plan_id FROM subscription_products WHERE id = :productId
            """;

    private static final String GET_USER_GENDER_SQL = """
            SELECT gender FROM profiles WHERE user_id = :userId
            """;

    // ── Campaign methods ─────────────────────────────────────────────────────

    private List<String> buildCountryCodes(String countryCode) {
        if (countryCode == null || "GLOBAL".equalsIgnoreCase(countryCode)) {
            return List.of("GLOBAL");
        }
        return List.of(countryCode, "GLOBAL");
    }

    public Optional<CampaignRow> findCampaignById(UUID id) {
        return jdbc.query(FIND_CAMPAIGN_BY_ID_SQL, Map.of("id", id), this::mapCampaignRow)
                .stream().findFirst();
    }

    public Optional<CampaignRow> findCampaignByKey(String key) {
        return jdbc.query(FIND_CAMPAIGN_BY_KEY_SQL, Map.of("key", key), this::mapCampaignRow)
                .stream().findFirst();
    }

    public List<CampaignRow> findActiveCampaignsByTriggerAndProduct(
            String triggerType, UUID productId, String countryCode, Instant now) {
        List<String> countryCodes = buildCountryCodes(countryCode);
        var params = new MapSqlParameterSource()
                .addValue("triggerType", triggerType)
                .addValue("productId", productId)
                .addValue("countryCodes", countryCodes)
                .addValue("primaryCountry", countryCode)
                .addValue("now", java.sql.Timestamp.from(now));
        return jdbc.query(FIND_ACTIVE_CAMPAIGNS_BY_TRIGGER_PRODUCT_SQL, params, this::mapCampaignRow);
    }

    public List<CampaignRow> findActiveCampaignsByTrigger(
            String triggerType, String countryCode, Instant now) {
        List<String> countryCodes = buildCountryCodes(countryCode);
        var params = new MapSqlParameterSource()
                .addValue("triggerType", triggerType)
                .addValue("countryCodes", countryCodes)
                .addValue("primaryCountry", countryCode)
                .addValue("now", java.sql.Timestamp.from(now));
        return jdbc.query(FIND_ACTIVE_CAMPAIGNS_BY_TRIGGER_SQL, params, this::mapCampaignRow);
    }

    public List<CampaignRow> findActivePurchaseCampaigns(
            UUID productId, String countryCode, Instant now) {
        List<String> countryCodes = buildCountryCodes(countryCode);
        var params = new MapSqlParameterSource()
                .addValue("productId", productId)
                .addValue("countryCodes", countryCodes)
                .addValue("primaryCountry", countryCode)
                .addValue("now", java.sql.Timestamp.from(now));
        return jdbc.query(FIND_ACTIVE_PURCHASE_CAMPAIGNS_SQL, params, this::mapCampaignRow);
    }

    public UUID insertCampaign(CampaignRow proto, UUID createdBy) {
        var params = new MapSqlParameterSource()
                .addValue("campaignKey", proto.campaignKey())
                .addValue("name", proto.name())
                .addValue("description", proto.description())
                .addValue("triggerType", proto.triggerType())
                .addValue("eligibilityType", proto.eligibilityType())
                .addValue("benefitType", proto.benefitType())
                .addValue("discountType", proto.discountType())
                .addValue("discountValue", proto.discountValue())
                .addValue("discountCurrency", proto.discountCurrency())
                .addValue("subscriptionProductId", proto.subscriptionProductId())
                .addValue("countryCode", proto.countryCode())
                .addValue("durationDays", proto.durationDays())
                .addValue("newUserWindowDays", proto.newUserWindowDays())
                .addValue("maxRedemptions", proto.maxRedemptions())
                .addValue("maxRedemptionsPerUser", proto.maxRedemptionsPerUser())
                .addValue("priority", proto.priority())
                .addValue("startsAt", proto.startsAt() != null ? java.sql.Timestamp.from(proto.startsAt()) : null)
                .addValue("endsAt", proto.endsAt() != null ? java.sql.Timestamp.from(proto.endsAt()) : null)
                .addValue("targetGender", proto.targetGender())
                .addValue("createdBy", createdBy);
        return jdbc.queryForObject(INSERT_CAMPAIGN_SQL, params, UUID.class);
    }

    public void updateCampaign(UUID id, String name, String description,
                                Integer maxRedemptions, Integer maxPerUser,
                                Integer priority, Instant endsAt,
                                String targetGender) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("name", name)
                .addValue("description", description)
                .addValue("maxRedemptions", maxRedemptions)
                .addValue("maxPerUser", maxPerUser)
                .addValue("priority", priority)
                .addValue("endsAt", endsAt != null ? java.sql.Timestamp.from(endsAt) : null)
                .addValue("targetGender", targetGender);
        jdbc.update(UPDATE_CAMPAIGN_SQL, params);
    }

    public void updateCampaignStatus(UUID id, String status) {
        jdbc.update(UPDATE_CAMPAIGN_STATUS_SQL, Map.of("id", id, "status", status));
    }

    public List<CampaignRow> listCampaigns(String status, int limit, int offset) {
        var params = new MapSqlParameterSource()
                .addValue("status", status, Types.VARCHAR)
                .addValue("limit", limit)
                .addValue("offset", offset);
        return jdbc.query(LIST_CAMPAIGNS_SQL, params, this::mapCampaignRow);
    }

    public long countCampaigns(String status) {
        var params = new MapSqlParameterSource()
                .addValue("status", status, Types.VARCHAR);
        Long n = jdbc.queryForObject(COUNT_CAMPAIGNS_SQL, params, Long.class);
        return n != null ? n : 0;
    }

    public boolean atomicReserveCapacity(UUID campaignId, UUID userId, int maxPerUser) {
        Integer userCount = jdbc.queryForObject(COUNT_ACTIVE_REDEMPTIONS_FOR_USER_SQL,
                Map.of("campaignId", campaignId, "userId", userId), Integer.class);
        if (userCount != null && userCount >= maxPerUser) {
            log.debug("Per-user redemption limit reached: campaign={} user={} count={}/{}",
                    campaignId, userId, userCount, maxPerUser);
            return false;
        }
        int rows = jdbc.update(ATOMIC_RESERVE_SQL, Map.of("campaignId", campaignId));
        return rows > 0;
    }

    public void fulfillReservation(UUID campaignId) {
        jdbc.update(ATOMIC_FULFILL_SQL, Map.of("campaignId", campaignId));
    }

    public void releaseReservation(UUID campaignId) {
        jdbc.update(RELEASE_RESERVATION_SQL, Map.of("campaignId", campaignId));
    }

    public void incrementFulfilled(UUID campaignId) {
        jdbc.update(INCREMENT_FULFILLED_SQL, Map.of("campaignId", campaignId));
    }

    // ── Redemption methods ───────────────────────────────────────────────────

    public UUID insertRedemption(UUID campaignId, UUID userId, UUID paymentOfferId, UUID paymentOrderId,
                                  String status, String eligibilityCountry, String eligibilityGender,
                                  long originalAmount, long discountAmount, long finalAmount,
                                  String currency) {
        var params = new MapSqlParameterSource()
                .addValue("campaignId", campaignId)
                .addValue("userId", userId)
                .addValue("paymentOfferId", paymentOfferId)
                .addValue("paymentOrderId", paymentOrderId)
                .addValue("status", status)
                .addValue("eligibilityCountry", eligibilityCountry)
                .addValue("eligibilityGender", eligibilityGender)
                .addValue("originalAmount", originalAmount)
                .addValue("discountAmount", discountAmount)
                .addValue("finalAmount", finalAmount)
                .addValue("currency", currency);
        return jdbc.queryForObject(INSERT_REDEMPTION_SQL, params, UUID.class);
    }

    public void fulfillRedemption(UUID redemptionId, UUID subscriptionId) {
        jdbc.update(UPDATE_REDEMPTION_FULFILLED_SQL,
                Map.of("id", redemptionId, "subscriptionId", subscriptionId));
    }

    public void cancelRedemption(UUID redemptionId, String failureCode, String failureReason) {
        var params = new MapSqlParameterSource()
                .addValue("id", redemptionId)
                .addValue("failureCode", failureCode)
                .addValue("failureReason", failureReason);
        jdbc.update(UPDATE_REDEMPTION_CANCELLED_SQL, params);
    }

    public void expireRedemption(UUID redemptionId) {
        jdbc.update(UPDATE_REDEMPTION_EXPIRED_SQL, Map.of("id", redemptionId));
    }

    public Optional<RedemptionRow> findReservedRedemptionByOrderId(UUID orderId) {
        return jdbc.query(FIND_RESERVED_REDEMPTION_BY_ORDER_SQL,
                Map.of("orderId", orderId), this::mapRedemptionRow)
                .stream().findFirst();
    }

    public void fulfillPurchaseRedemptionByOrderId(UUID orderId, UUID subscriptionId) {
        Optional<RedemptionRow> redemption = findReservedRedemptionByOrderId(orderId);
        if (redemption.isEmpty()) return;

        RedemptionRow r = redemption.get();
        fulfillRedemption(r.id(), subscriptionId);
        fulfillReservation(r.campaignId());
        log.info("Purchase redemption fulfilled: redemption={} campaign={} order={}",
                r.id(), r.campaignId(), orderId);
    }

    public void cancelRedemptionByOrderId(UUID orderId, String failureCode) {
        var params = new MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("failureCode", failureCode);
        List<Map<String, Object>> rows = jdbc.queryForList(CANCEL_REDEMPTIONS_BY_ORDER_SQL, params);
        for (var row : rows) {
            UUID campaignId = (UUID) row.get("campaign_id");
            releaseReservation(campaignId);
            log.info("Purchase redemption cancelled: campaign={} order={}", campaignId, orderId);
        }
    }

    private static final String CANCEL_PENDING_REDEMPTIONS_FOR_USER_SQL = """
            UPDATE promotion_redemptions
            SET status = 'CANCELLED', cancelled_at = NOW(), updated_at = NOW(),
                failure_code = :failureCode
            WHERE user_id = :userId
              AND status IN ('RESERVED', 'PROVIDER_PENDING')
            RETURNING campaign_id
            """;

    public int cancelPendingRedemptionsForUser(UUID userId, String failureCode) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("failureCode", failureCode);
        List<Map<String, Object>> rows = jdbc.queryForList(CANCEL_PENDING_REDEMPTIONS_FOR_USER_SQL, params);
        for (var row : rows) {
            UUID campaignId = (UUID) row.get("campaign_id");
            releaseReservation(campaignId);
            log.info("Pending promotion redemption cancelled: campaign={} user={}", campaignId, userId);
        }
        return rows.size();
    }

    public int expireStaleRedemptionsOlderThan(Instant cutoff) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                EXPIRE_STALE_REDEMPTIONS_SQL, Map.of("cutoff", java.sql.Timestamp.from(cutoff)));
        int total = 0;
        for (var row : rows) {
            UUID campaignId = (UUID) row.get("campaign_id");
            int cnt = ((Number) row.get("cnt")).intValue();
            // Release reserved capacity (only RESERVED ones were actually counted toward capacity)
            for (int i = 0; i < cnt; i++) {
                releaseReservation(campaignId);
            }
            total += cnt;
        }
        return total;
    }

    public List<RedemptionRow> listRedemptionsByCampaign(UUID campaignId, int limit, int offset) {
        var params = new MapSqlParameterSource()
                .addValue("campaignId", campaignId)
                .addValue("limit", limit)
                .addValue("offset", offset);
        return jdbc.query(LIST_REDEMPTIONS_BY_CAMPAIGN_SQL, params, this::mapRedemptionRow);
    }

    public List<UserRedemptionRow> listRedemptionsByUser(UUID userId, int limit, int offset) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("limit", limit)
                .addValue("offset", offset);
        return jdbc.query(LIST_REDEMPTIONS_BY_USER_SQL, params, this::mapUserRedemptionRow);
    }

    // ── User / plan helpers ──────────────────────────────────────────────────

    public Optional<Instant> getUserCreatedAt(UUID userId) {
        List<Instant> results = jdbc.query(USER_CREATED_AT_SQL, Map.of("userId", userId),
                (rs, rowNum) -> rs.getObject("created_at", java.time.OffsetDateTime.class)
                        .toInstant());
        return results.stream().findFirst();
    }

    public boolean hasAnySubscription(UUID userId) {
        Boolean result = jdbc.queryForObject(HAS_ANY_SUBSCRIPTION_SQL,
                Map.of("userId", userId), Boolean.class);
        return Boolean.TRUE.equals(result);
    }

    public boolean hasActiveSubscription(UUID userId) {
        Boolean result = jdbc.queryForObject(HAS_ACTIVE_SUBSCRIPTION_SQL,
                Map.of("userId", userId), Boolean.class);
        return Boolean.TRUE.equals(result);
    }

    public Optional<UUID> findPlanIdForProduct(UUID subscriptionProductId) {
        List<UUID> results = jdbc.query(FIND_PLAN_ID_FOR_PRODUCT_SQL,
                Map.of("productId", subscriptionProductId),
                (rs, rowNum) -> rs.getObject("plan_id", UUID.class));
        return results.stream().findFirst();
    }

    public Optional<String> getUserGender(UUID userId) {
        List<String> results = jdbc.query(GET_USER_GENDER_SQL,
                Map.of("userId", userId),
                (rs, rowNum) -> rs.getString("gender"));
        return results.stream().findFirst();
    }

    // ── Mappers ──────────────────────────────────────────────────────────────

    private CampaignRow mapCampaignRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new CampaignRow(
                rs.getObject("id", UUID.class),
                rs.getString("campaign_key"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("trigger_type"),
                rs.getString("eligibility_type"),
                rs.getString("benefit_type"),
                rs.getString("discount_type"),
                rs.getObject("discount_value") != null ? rs.getLong("discount_value") : null,
                rs.getString("discount_currency"),
                rs.getObject("subscription_product_id", UUID.class),
                rs.getString("country_code"),
                rs.getObject("duration_days") != null ? rs.getInt("duration_days") : null,
                rs.getObject("new_user_window_days") != null ? rs.getInt("new_user_window_days") : null,
                rs.getObject("max_redemptions") != null ? rs.getInt("max_redemptions") : null,
                rs.getInt("max_redemptions_per_user"),
                rs.getInt("reserved_count"),
                rs.getInt("fulfilled_count"),
                rs.getInt("priority"),
                rs.getObject("starts_at", java.time.OffsetDateTime.class).toInstant(),
                rs.getObject("ends_at") != null
                        ? rs.getObject("ends_at", java.time.OffsetDateTime.class).toInstant() : null,
                rs.getString("status"),
                rs.getString("target_gender"),
                rs.getObject("created_by_user_id", UUID.class),
                rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", java.time.OffsetDateTime.class).toInstant()
        );
    }

    private RedemptionRow mapRedemptionRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new RedemptionRow(
                rs.getObject("id", UUID.class),
                rs.getObject("campaign_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("subscription_id", UUID.class),
                rs.getObject("payment_offer_id", UUID.class),
                rs.getObject("payment_order_id", UUID.class),
                rs.getString("status"),
                rs.getString("eligibility_country"),
                rs.getString("eligibility_gender"),
                rs.getObject("original_amount_minor") != null ? rs.getLong("original_amount_minor") : null,
                rs.getObject("discount_amount_minor") != null ? rs.getLong("discount_amount_minor") : null,
                rs.getObject("final_amount_minor") != null ? rs.getLong("final_amount_minor") : null,
                rs.getString("currency"),
                rs.getObject("reserved_at", java.time.OffsetDateTime.class).toInstant(),
                rs.getObject("fulfilled_at") != null
                        ? rs.getObject("fulfilled_at", java.time.OffsetDateTime.class).toInstant() : null,
                rs.getObject("cancelled_at") != null
                        ? rs.getObject("cancelled_at", java.time.OffsetDateTime.class).toInstant() : null,
                rs.getObject("expired_at") != null
                        ? rs.getObject("expired_at", java.time.OffsetDateTime.class).toInstant() : null,
                rs.getObject("failed_at") != null
                        ? rs.getObject("failed_at", java.time.OffsetDateTime.class).toInstant() : null,
                rs.getString("failure_code"),
                rs.getString("failure_reason"),
                rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", java.time.OffsetDateTime.class).toInstant()
        );
    }

    private UserRedemptionRow mapUserRedemptionRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new UserRedemptionRow(
                rs.getObject("id", UUID.class),
                rs.getObject("campaign_id", UUID.class),
                rs.getString("campaign_key"),
                rs.getString("campaign_name"),
                rs.getString("benefit_type"),
                rs.getObject("duration_days") != null ? rs.getInt("duration_days") : null,
                rs.getObject("user_id", UUID.class),
                rs.getObject("subscription_id", UUID.class),
                rs.getObject("payment_order_id", UUID.class),
                rs.getString("status"),
                rs.getObject("original_amount_minor") != null ? rs.getLong("original_amount_minor") : null,
                rs.getObject("discount_amount_minor") != null ? rs.getLong("discount_amount_minor") : null,
                rs.getObject("final_amount_minor") != null ? rs.getLong("final_amount_minor") : null,
                rs.getString("currency"),
                rs.getObject("reserved_at", java.time.OffsetDateTime.class).toInstant(),
                rs.getObject("fulfilled_at") != null
                        ? rs.getObject("fulfilled_at", java.time.OffsetDateTime.class).toInstant() : null,
                rs.getObject("cancelled_at") != null
                        ? rs.getObject("cancelled_at", java.time.OffsetDateTime.class).toInstant() : null,
                rs.getObject("expired_at") != null
                        ? rs.getObject("expired_at", java.time.OffsetDateTime.class).toInstant() : null,
                rs.getString("failure_code"),
                rs.getString("subscription_status"),
                rs.getObject("subscription_period_end") != null
                        ? rs.getObject("subscription_period_end", java.time.OffsetDateTime.class).toInstant() : null
        );
    }
}
