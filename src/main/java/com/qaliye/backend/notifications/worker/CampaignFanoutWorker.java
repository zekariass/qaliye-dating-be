package com.qaliye.backend.notifications.worker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaliye.backend.notifications.service.NotificationOutboxService;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class CampaignFanoutWorker implements Job {

    private static final Logger log = LoggerFactory.getLogger(CampaignFanoutWorker.class);
    private static final int BATCH_SIZE = 500;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String FIND_CAMPAIGNS_SQL = """
            SELECT id, status, audience_definition::text AS audience_definition
            FROM notification_campaigns
            WHERE status = 'SENDING'
               OR (status = 'SCHEDULED' AND scheduled_at <= NOW())
            ORDER BY created_at
            """;

    private static final String AUTO_START_CAMPAIGN_SQL = """
            UPDATE notification_campaigns
            SET status     = 'SENDING',
                started_at = NOW(),
                updated_at = NOW()
            WHERE id     = :id
              AND status = 'SCHEDULED'
            """;

    private static final String MARK_COMPLETED_SQL = """
            UPDATE notification_campaigns
            SET status       = 'COMPLETED',
                completed_at = NOW(),
                updated_at   = NOW()
            WHERE id     = :id
              AND status = 'SENDING'
            """;

    private static final String UPDATE_LAST_MARKETING_SENT_SQL = """
            UPDATE user_notification_preferences
            SET last_marketing_sent_at = NOW(),
                updated_at             = NOW()
            WHERE user_id = :userId
            """;

    @Autowired private NotificationOutboxService outboxService;
    @Autowired private NamedParameterJdbcTemplate jdbc;
    @Autowired private TransactionTemplate txTemplate;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        try {
            List<CampaignRow> campaigns = findCampaigns();
            for (CampaignRow campaign : campaigns) {
                try {
                    txTemplate.execute(status -> {
                        if ("SCHEDULED".equals(campaign.status())) {
                            autoStart(campaign.id());
                        }
                        processCampaign(campaign);
                        return null;
                    });
                } catch (Exception e) {
                    log.error("CampaignFanoutWorker: error processing campaign {}: {}",
                            campaign.id(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("CampaignFanoutWorker failed: {}", e.getMessage());
            throw new JobExecutionException(e, false);
        }
    }

    private List<CampaignRow> findCampaigns() {
        return jdbc.query(FIND_CAMPAIGNS_SQL, new MapSqlParameterSource(),
                (rs, row) -> new CampaignRow(
                        (UUID) rs.getObject("id"),
                        rs.getString("status"),
                        parseAudience(rs.getString("audience_definition"))));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseAudience(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            Map<String, Object> map = MAPPER.readValue(json, new TypeReference<>() {});
            return map.isEmpty() ? Collections.emptyMap() : map;
        } catch (Exception e) {
            log.warn("CampaignFanoutWorker: failed to parse audience_definition, targeting all: {}",
                    e.getMessage());
            return Collections.emptyMap();
        }
    }

    private void autoStart(UUID campaignId) {
        int updated = jdbc.update(AUTO_START_CAMPAIGN_SQL,
                new MapSqlParameterSource("id", campaignId));
        if (updated > 0) {
            log.info("CampaignFanoutWorker: auto-started scheduled campaign {}", campaignId);
        }
    }

    private void processCampaign(CampaignRow campaign) {
        QueryAndParams qp = buildEligibleUsersQuery(campaign.id(), campaign.audience());
        List<UUID> userIds = jdbc.queryForList(qp.sql(), qp.params(), UUID.class);

        if (userIds.isEmpty()) {
            int updated = jdbc.update(MARK_COMPLETED_SQL,
                    new MapSqlParameterSource("id", campaign.id()));
            if (updated > 0) {
                log.info("CampaignFanoutWorker: campaign {} completed", campaign.id());
            }
            return;
        }

        OffsetDateTime now = OffsetDateTime.now();
        int enqueued = 0;
        for (UUID userId : userIds) {
            try {
                outboxService.createMarketingEvent(campaign.id(), userId, now);
                jdbc.update(UPDATE_LAST_MARKETING_SENT_SQL,
                        new MapSqlParameterSource("userId", userId));
                enqueued++;
            } catch (Exception e) {
                log.error("CampaignFanoutWorker: failed to enqueue user {} for campaign {}: {}",
                        userId, campaign.id(), e.getMessage());
            }
        }

        log.info("CampaignFanoutWorker: campaign {} — enqueued {}/{} outbox events in this batch",
                campaign.id(), enqueued, userIds.size());
    }

    private QueryAndParams buildEligibleUsersQuery(UUID campaignId, Map<String, Object> audience) {
        List<String> residencyTypes = toStringList(audience.get("residencyTypes"));
        List<String> countries      = toStringList(audience.get("countries"));
        List<String> relationshipIntentions = toStringList(audience.get("relationshipIntentions"));
        List<String> maritalStatuses       = toStringList(audience.get("maritalStatuses"));

        boolean needsProfile = audience.containsKey("gender")
                || audience.containsKey("ageMin")
                || audience.containsKey("ageMax")
                || !residencyTypes.isEmpty()
                || !relationshipIntentions.isEmpty()
                || !maritalStatuses.isEmpty()
                || audience.containsKey("profileCompletionMin")
                || audience.containsKey("profileCompletionMax")
                || Boolean.TRUE.equals(audience.get("onboardedOnly"));

        boolean needsAddress = !countries.isEmpty();

        StringBuilder sql = new StringBuilder("""
                SELECT u.id
                FROM app_users u
                JOIN user_notification_preferences unp ON unp.user_id = u.id
                """);

        if (needsProfile) {
            sql.append("JOIN profiles p ON p.user_id = u.id\n");
        }
        if (needsAddress) {
            sql.append("LEFT JOIN addresses a ON a.id = u.address_id\n");
        }

        sql.append("""
                WHERE u.status = 'ACTIVE'
                  AND unp.push_enabled = TRUE
                  AND unp.marketing_notifications_enabled = TRUE
                  AND unp.marketing_notifications_opted_in_at IS NOT NULL
                  AND NULLIF(BTRIM(unp.marketing_notifications_consent_version), '') IS NOT NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM notification_outbox_events noe
                      WHERE noe.campaign_id       = :campaignId
                        AND noe.recipient_user_id = u.id
                  )
                """);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("campaignId", campaignId)
                .addValue("batchSize", BATCH_SIZE);

        if (audience.containsKey("gender")) {
            sql.append("  AND p.gender = :gender\n");
            params.addValue("gender", String.valueOf(audience.get("gender")));
        }
        if (audience.containsKey("ageMin")) {
            sql.append("  AND calculate_age(p.date_of_birth, CURRENT_DATE) >= :ageMin\n");
            params.addValue("ageMin", ((Number) audience.get("ageMin")).intValue());
        }
        if (audience.containsKey("ageMax")) {
            sql.append("  AND calculate_age(p.date_of_birth, CURRENT_DATE) <= :ageMax\n");
            params.addValue("ageMax", ((Number) audience.get("ageMax")).intValue());
        }
        if (!residencyTypes.isEmpty()) {
            sql.append("  AND p.residency_type IN (:residencyTypes)\n");
            params.addValue("residencyTypes", residencyTypes);
        }
        if (Boolean.TRUE.equals(audience.get("onboardedOnly"))) {
            sql.append("  AND p.is_onboarded = TRUE\n");
        }
        if (needsAddress) {
            sql.append("  AND a.country_code IN (:countries)\n");
            params.addValue("countries", countries);
        }
        if (Boolean.TRUE.equals(audience.get("verifiedOnly"))) {
            sql.append("  AND u.verification_status = 'VERIFIED'\n");
        }
        if (Boolean.TRUE.equals(audience.get("unVerifiedOnly"))) {
            sql.append("  AND u.verification_status != 'VERIFIED'\n");
        }
        if (Boolean.TRUE.equals(audience.get("premiumOnly"))) {
            sql.append("""
                      AND EXISTS (
                          SELECT 1 FROM user_subscriptions us
                          WHERE us.user_id = u.id
                            AND us.status IN ('ACTIVE', 'GRACE_PERIOD')
                      )
                    """);
        }
        if (Boolean.TRUE.equals(audience.get("nonPremiumOnly"))) {
            sql.append("""
                      AND NOT EXISTS (
                          SELECT 1 FROM user_subscriptions us
                          WHERE us.user_id = u.id
                            AND us.status IN ('ACTIVE', 'GRACE_PERIOD')
                      )
                    """);
        }
        if (audience.containsKey("lastActiveDays")) {
            sql.append("  AND u.last_active_at >= NOW() - (:lastActiveDays || ' days')::interval\n");
            params.addValue("lastActiveDays", ((Number) audience.get("lastActiveDays")).intValue());
        }
        if (audience.containsKey("lastActiveDaysMax")) {
            sql.append("  AND u.last_active_at < NOW() - (:lastActiveDaysMax || ' days')::interval\n");
            params.addValue("lastActiveDaysMax", ((Number) audience.get("lastActiveDaysMax")).intValue());
        }
        if (audience.containsKey("accountAgeDays")) {
            sql.append("  AND u.created_at <= NOW() - (:accountAgeDays || ' days')::interval\n");
            params.addValue("accountAgeDays", ((Number) audience.get("accountAgeDays")).intValue());
        }
        if (!relationshipIntentions.isEmpty()) {
            sql.append("  AND p.relationship_intention IN (:relationshipIntentions)\n");
            params.addValue("relationshipIntentions", relationshipIntentions);
        }
        if (!maritalStatuses.isEmpty()) {
            sql.append("  AND p.marital_status IN (:maritalStatuses)\n");
            params.addValue("maritalStatuses", maritalStatuses);
        }
        if (audience.containsKey("profileCompletionMin")) {
            sql.append("  AND p.profile_completion_score >= :profileCompletionMin\n");
            params.addValue("profileCompletionMin", ((Number) audience.get("profileCompletionMin")).intValue());
        }
        if (audience.containsKey("profileCompletionMax")) {
            sql.append("  AND p.profile_completion_score <= :profileCompletionMax\n");
            params.addValue("profileCompletionMax", ((Number) audience.get("profileCompletionMax")).intValue());
        }

        sql.append("ORDER BY u.id\nLIMIT :batchSize\n");

        return new QueryAndParams(sql.toString(), params);
    }

    private List<String> toStringList(Object value) {
        if (!(value instanceof List<?> raw) || raw.isEmpty()) return Collections.emptyList();
        return raw.stream().map(Object::toString).toList();
    }

    private record CampaignRow(UUID id, String status, Map<String, Object> audience) {}
    private record QueryAndParams(String sql, MapSqlParameterSource params) {}
}
