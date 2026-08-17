package com.qaliye.backend.discovery.service;

import com.qaliye.backend.billing.repository.ActionLimitRepository;
import com.qaliye.backend.billing.service.ActionCostService;
import com.qaliye.backend.billing.service.CreditService;
import com.qaliye.backend.discovery.dto.RevisitPassesResponse;
import com.qaliye.backend.discovery.exception.ActorIneligibleException;
import com.qaliye.backend.discovery.exception.ActionLimitExceededException;
import com.qaliye.backend.discovery.repository.DiscoveryActionRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
public class RevisitPassesService {

    private static final int CANDIDATE_FETCH_MULTIPLIER = 3;

    private final DiscoveryActionRepository actionRepo;
    private final DiscoveryQueryService queryService;
    private final NamedParameterJdbcTemplate jdbc;
    private final ActionCostService actionCostService;
    private final CreditService creditService;
    private final ActionLimitRepository actionLimitRepo;

    public RevisitPassesService(DiscoveryActionRepository actionRepo,
                                 DiscoveryQueryService queryService,
                                 NamedParameterJdbcTemplate jdbc,
                                 ActionCostService actionCostService,
                                 CreditService creditService,
                                 ActionLimitRepository actionLimitRepo) {
        this.actionRepo = actionRepo;
        this.queryService = queryService;
        this.jdbc = jdbc;
        this.actionCostService = actionCostService;
        this.creditService = creditService;
        this.actionLimitRepo = actionLimitRepo;
    }

    /**
     * Selects the actor's most-recently-active PASS actions whose targets still
     * satisfy all current Discovery eligibility rules, reverses them in-place
     * with reason REVISIT_PASSES, and returns the count reversed.
     *
     * Rules enforced:
     * - Target must be account-active, visible, onboarded, with approved primary photo.
     * - Target must match actor's current gender / age / residency / distance prefs.
     * - No active block in either direction.
     * - No match record (any status) between the pair.
     * - Actor must not already have an active LIKE or SUPERLIKE against the target.
     * - No entitlement is consumed; no limit counters are incremented.
     * - No push notification is dispatched.
     */
    private static final String FIND_ELIGIBLE_PASSES_SQL = """
            SELECT uda.id AS action_id
            FROM user_discovery_actions uda
            JOIN profiles p  ON p.user_id  = uda.target_user_id
            JOIN app_users au ON au.id      = uda.target_user_id
            JOIN addresses a  ON a.id       = au.address_id
            WHERE uda.actor_user_id = :actorId
              AND uda.action_type   = 'PASS'
              AND uda.status        = 'ACTIVE'
              AND p.is_visible      = TRUE
              AND p.is_onboarded    = TRUE
              AND au.status         = 'ACTIVE'
              AND au.deleted_at     IS NULL
              AND (p.discovery_mode <> 'INCOGNITO'
                   OR EXISTS (
                       SELECT 1 FROM user_discovery_actions uda_inc
                       WHERE uda_inc.actor_user_id  = p.user_id
                         AND uda_inc.target_user_id = :actorId
                         AND uda_inc.action_type   IN ('LIKE', 'SUPERLIKE')
                         AND uda_inc.status         = 'ACTIVE'
                   ))
              AND p.gender          = :targetGender
              AND calculate_age(p.date_of_birth) BETWEEN :minAge AND :maxAge
              AND (:showVerifiedOnly = FALSE OR p.is_verified = TRUE)
              AND p.residency_type  = ANY(:residencyTypes::TEXT[])
              AND (:langPrefIds = '{}' OR p.language_ids && :langPrefIds::UUID[])
              AND (:ethPrefIds  = '{}' OR p.ethnicity_ids && :ethPrefIds::UUID[])
              AND (:hasChildrenPref = 'any' OR
                   (:hasChildrenPref = 'yes' AND p.has_children = TRUE) OR
                   (:hasChildrenPref = 'no'  AND p.has_children IS DISTINCT FROM TRUE))
              AND (:wantsChildrenPref = 'any' OR
                   (:wantsChildrenPref = 'yes' AND p.wants_children = TRUE) OR
                   (:wantsChildrenPref = 'no'  AND p.wants_children IS DISTINCT FROM TRUE) OR
                   (:wantsChildrenPref = 'not_sure' AND p.wants_children IS NULL) OR
                   (:wantsChildrenPref = 'open_to_discussion' AND p.wants_children IS DISTINCT FROM FALSE))
              AND (:religionPrefs = '{}' OR p.religion = ANY(:religionPrefs::TEXT[]))
              AND (:specificCountryCodes = '{}' OR a.country_code = ANY(:specificCountryCodes::TEXT[]))
              AND EXISTS (
                  SELECT 1 FROM profile_photos pp
                  WHERE pp.user_id           = p.user_id
                    AND pp.is_primary        = TRUE
                    AND pp.moderation_status = 'APPROVED'
                    AND pp.deleted_at        IS NULL
              )
              AND NOT EXISTS (
                  SELECT 1 FROM user_blocks ub
                  WHERE ub.status = 'ACTIVE'
                    AND (
                        (ub.blocker_user_id = :actorId AND ub.blocked_user_id = uda.target_user_id)
                        OR
                        (ub.blocker_user_id = uda.target_user_id AND ub.blocked_user_id = :actorId)
                    )
              )
              AND NOT EXISTS (
                  SELECT 1 FROM matches m
                  WHERE (m.user_one_id = :actorId AND m.user_two_id = uda.target_user_id)
                     OR (m.user_one_id = uda.target_user_id AND m.user_two_id = :actorId)
              )
              AND NOT EXISTS (
                  SELECT 1 FROM user_discovery_actions uda2
                  WHERE uda2.actor_user_id  = :actorId
                    AND uda2.target_user_id = uda.target_user_id
                    AND uda2.action_type   IN ('LIKE', 'SUPERLIKE')
                    AND uda2.status         = 'ACTIVE'
              )
              AND (
                  :skipDistance
                  OR :locationMode <> 'nearby'
                  OR ST_DWithin(:actorCoords::geography, a.coords::geography, :maxDistanceKm * 1000.0)
              )
            ORDER BY uda.created_at DESC, uda.id DESC
            LIMIT :limit
            FOR UPDATE OF uda SKIP LOCKED
            """;

    @Transactional
    public RevisitPassesResponse revisitPasses(UUID actorId, int count) {
        DiscoveryQueryService.ActorContext ctx = queryService.loadActorContext(actorId);
        if (ctx == null) {
            throw ActorIneligibleException.profileIncomplete();
        }

        // Evaluate RETURN_PASSED_PROFILE action cost from subscription_plan_limit_and_cost
        ActionCostService.ActionCostResult cost = actionCostService.evaluate(actorId, "RETURN_PASSED_PROFILE");
        if (cost.isBlocked()) {
            throw new ActionLimitExceededException("RETURN_PASSED_PROFILE", cost.periodType());
        }
        if (cost.ruleId() != null && cost.limitValue() != null) {
            actionLimitRepo.ensureExists(actorId, cost.ruleId(), cost.periodStart(), cost.periodEnd());
            boolean incremented = actionLimitRepo
                    .tryIncrementUnderLimit(actorId, cost.ruleId(), cost.periodStart(), cost.limitValue())
                    .isPresent();
            if (!incremented && !cost.requiresCredits()) {
                throw new ActionLimitExceededException("RETURN_PASSED_PROFILE", cost.periodType());
            }
        }

        if (cost.requiresCredits()) {
            creditService.consumeCredits(actorId, cost.creditCost(), "RETURN_PASSED_PROFILE",
                    "revisit-" + actorId + "-" + System.currentTimeMillis());
        }

        String residencyParam = buildArrayParam(resolveResidencyTypes(ctx));

        var params = new MapSqlParameterSource()
                .addValue("actorId", actorId)
                .addValue("skipDistance", false)
                .addValue("actorCoords", ctx.coordsEwkt())
                .addValue("targetGender", ctx.interestedInGender())
                .addValue("minAge", ctx.minAge())
                .addValue("maxAge", ctx.maxAge() > 0 ? ctx.maxAge() : 120)
                .addValue("maxDistanceKm", ctx.maxDistanceKm() > 0 ? ctx.maxDistanceKm() : 500)
                .addValue("residencyTypes", residencyParam)
                .addValue("showVerifiedOnly", ctx.showVerifiedOnly())
                .addValue("locationMode", ctx.locationMode() != null ? ctx.locationMode() : "nearby")
                .addValue("langPrefIds", buildUuidArrayParam(Arrays.asList(ctx.languagePreferenceIds())))
                .addValue("ethPrefIds", buildUuidArrayParam(Arrays.asList(ctx.ethnicityPreferenceIds())))
                .addValue("hasChildrenPref", ctx.hasChildrenPreference() != null ? ctx.hasChildrenPreference() : "any")
                .addValue("wantsChildrenPref", ctx.wantsChildrenPreference() != null ? ctx.wantsChildrenPreference() : "any")
                .addValue("religionPrefs", buildArrayParam(ctx.religionPreferences() != null ? ctx.religionPreferences() : new String[0]))
                .addValue("specificCountryCodes", buildArrayParam(ctx.specificCountryCodes() != null ? ctx.specificCountryCodes() : new String[0]))
                .addValue("limit", count * CANDIDATE_FETCH_MULTIPLIER);

        List<UUID> candidateIds = jdbc.query(FIND_ELIGIBLE_PASSES_SQL, params,
                (rs, rowNum) -> rs.getObject("action_id", UUID.class));

        int reopenedCount = 0;
        for (UUID actionId : candidateIds) {
            if (reopenedCount >= count) {
                break;
            }
            int reversed = actionRepo.reversePassForRevisit(actionId);
            if (reversed > 0) {
                reopenedCount++;
            }
        }

        return new RevisitPassesResponse(true, reopenedCount);
    }

    private static final String[] ALL_RESIDENCY_TYPES = {"ETHIOPIA", "ERITREA", "DIASPORA"};

    private static String[] resolveResidencyTypes(
                                                    DiscoveryQueryService.ActorContext ctx) {
        return resolveFromLocationMode(ctx);
    }

    private static String[] resolveFromLocationMode(DiscoveryQueryService.ActorContext ctx) {
        return switch (ctx.locationMode()) {
            case "diaspora" -> new String[]{"DIASPORA"};
            case "specific_countries" -> ALL_RESIDENCY_TYPES;
            default -> ALL_RESIDENCY_TYPES;
        };
    }

    private static String buildArrayParam(String[] values) {
        return "{" + String.join(",", values) + "}";
    }

    private static String buildUuidArrayParam(List<UUID> ids) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(ids.get(i));
        }
        sb.append("}");
        return sb.toString();
    }
}
