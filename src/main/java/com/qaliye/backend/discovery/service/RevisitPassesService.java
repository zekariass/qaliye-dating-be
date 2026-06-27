package com.qaliye.backend.discovery.service;

import com.qaliye.backend.discovery.dto.RevisitPassesResponse;
import com.qaliye.backend.discovery.exception.ActorIneligibleException;
import com.qaliye.backend.discovery.repository.DiscoveryActionRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class RevisitPassesService {

    private static final int CANDIDATE_FETCH_MULTIPLIER = 3;

    private final DiscoveryActionRepository actionRepo;
    private final DiscoveryQueryService queryService;
    private final NamedParameterJdbcTemplate jdbc;

    public RevisitPassesService(DiscoveryActionRepository actionRepo,
                                 DiscoveryQueryService queryService,
                                 NamedParameterJdbcTemplate jdbc) {
        this.actionRepo = actionRepo;
        this.queryService = queryService;
        this.jdbc = jdbc;
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
              AND p.discovery_mode <> 'INCOGNITO'
              AND p.gender          = :targetGender
              AND calculate_age(p.date_of_birth) BETWEEN :minAge AND :maxAge
              AND (:showVerifiedOnly = FALSE OR p.is_verified = TRUE)
              AND p.residency_type  = ANY(:residencyTypes::TEXT[])
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
                  :locationFilter    <> 'NEARBY'
                  OR :openToLongDistance = TRUE
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

        String locationFilter = "NEARBY";
        String residencyParam = buildArrayParam(resolveResidencyTypes(locationFilter, ctx));

        var params = new MapSqlParameterSource()
                .addValue("actorId", actorId)
                .addValue("locationFilter", locationFilter)
                .addValue("actorCoords", ctx.coordsEwkt())
                .addValue("targetGender", ctx.interestedInGender())
                .addValue("minAge", ctx.minAge())
                .addValue("maxAge", ctx.maxAge())
                .addValue("maxDistanceKm", ctx.maxDistanceKm())
                .addValue("residencyTypes", residencyParam)
                .addValue("showVerifiedOnly", ctx.showVerifiedOnly())
                .addValue("openToLongDistance", ctx.openToLongDistance())
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

    private static String[] resolveResidencyTypes(String locationFilter,
                                                    DiscoveryQueryService.ActorContext ctx) {
        return switch (locationFilter) {
            case "ETHIOPIA" -> new String[]{"ETHIOPIA"};
            case "ERITREA"  -> new String[]{"ERITREA"};
            case "DIASPORA" -> new String[]{"DIASPORA"};
            case "ANYWHERE" -> new String[]{"ETHIOPIA", "ERITREA", "DIASPORA"};
            default         -> ctx.preferredResidencyTypes();
        };
    }

    private static String buildArrayParam(String[] values) {
        return "{" + String.join(",", values) + "}";
    }
}
