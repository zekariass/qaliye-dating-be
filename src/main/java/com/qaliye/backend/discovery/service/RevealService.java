package com.qaliye.backend.discovery.service;

import com.qaliye.backend.billing.repository.ActionLimitRepository;
import com.qaliye.backend.billing.service.ActionCostService;
import com.qaliye.backend.billing.service.CreditService;
import com.qaliye.backend.discovery.dto.RevealResponse;
import com.qaliye.backend.discovery.exception.ActionLimitExceededException;
import com.qaliye.backend.storage.SupabaseStorageService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RevealService {

    private static final String FIND_ACTION_FOR_RECEIVER_SQL = """
            SELECT id, actor_user_id, action_type, revealed_at
            FROM user_discovery_actions
            WHERE id = :actionId
              AND target_user_id = :callerId
              AND action_type IN ('LIKE', 'SUPERLIKE')
              AND status = 'ACTIVE'
            FOR UPDATE
            """;

    private static final String SET_REVEALED_AT_SQL = """
            UPDATE user_discovery_actions
            SET revealed_at = NOW()
            WHERE id = :actionId
            """;

    private static final String LOAD_ACTOR_PROFILE_SQL = """
            SELECT p.display_name,
                   p.date_of_birth,
                   pp.storage_path AS primary_photo_path
            FROM profiles p
            LEFT JOIN profile_photos pp
                ON pp.user_id = p.user_id
               AND pp.is_primary = TRUE
               AND pp.moderation_status = 'APPROVED'
               AND pp.deleted_at IS NULL
            WHERE p.user_id = :actorId
            LIMIT 1
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ActionCostService actionCostService;
    private final ActionLimitRepository actionLimitRepo;
    private final CreditService creditService;
    private final SupabaseStorageService storageService;

    public RevealService(NamedParameterJdbcTemplate jdbc,
                         ActionCostService actionCostService,
                         ActionLimitRepository actionLimitRepo,
                         CreditService creditService,
                         SupabaseStorageService storageService) {
        this.jdbc = jdbc;
        this.actionCostService = actionCostService;
        this.actionLimitRepo = actionLimitRepo;
        this.creditService = creditService;
        this.storageService = storageService;
    }

    @Transactional
    public RevealResponse reveal(UUID callerId, UUID actionId) {
        // 1. Load & lock action; verify caller is the receiver
        List<Map<String, Object>> rows = jdbc.queryForList(
                FIND_ACTION_FOR_RECEIVER_SQL,
                new MapSqlParameterSource("actionId", actionId).addValue("callerId", callerId));

        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "action_not_found");
        }

        Map<String, Object> row = rows.get(0);
        UUID actorId      = (UUID) row.get("actor_user_id");
        String actionType = (String) row.get("action_type");
        Object revealedAtObj = row.get("revealed_at");
        boolean alreadyRevealed = (revealedAtObj != null);

        // 2. Idempotency — already revealed, return without charging
        if (alreadyRevealed) {
            return buildResponse(actionId, actionType, actorId, true, 0L);
        }

        // 3. Evaluate SEE_WHO_LIKED_YOU entitlement
        ActionCostService.ActionCostResult cost = actionCostService.evaluate(callerId, "SEE_WHO_LIKED_YOU");

        if (cost.isBlocked()) {
            throw new ActionLimitExceededException("SEE_WHO_LIKED_YOU", cost.periodType());
        }

        boolean usedCredit = false;

        if (cost.ruleId() != null && cost.limitValue() != null) {
            // Limited allowance — atomically consume one slot
            actionLimitRepo.ensureExists(callerId, cost.ruleId(), cost.periodStart(), cost.periodEnd());
            boolean incremented = actionLimitRepo
                    .tryIncrementUnderLimit(callerId, cost.ruleId(), cost.periodStart(), cost.limitValue())
                    .isPresent();
            if (!incremented && !cost.requiresCredits()) {
                throw new ActionLimitExceededException("SEE_WHO_LIKED_YOU", cost.periodType());
            }
        }

        if (cost.requiresCredits()) {
            String idemKey = "reveal-" + actionId;
            creditService.consumeCredits(callerId, cost.creditCost(), "SEE_WHO_LIKED_YOU", idemKey);
            usedCredit = true;
        }

        // 4. Mark revealed
        jdbc.update(SET_REVEALED_AT_SQL, new MapSqlParameterSource("actionId", actionId));

        long creditBalance = usedCredit ? creditService.getBalance(callerId) : 0L;
        return buildResponse(actionId, actionType, actorId, false, creditBalance);
    }

    private RevealResponse buildResponse(UUID actionId, String actionType,
                                         UUID actorId, boolean idempotent, long creditBalance) {
        List<Map<String, Object>> profileRows = jdbc.queryForList(
                LOAD_ACTOR_PROFILE_SQL, new MapSqlParameterSource("actorId", actorId));

        String displayName = null;
        Integer age = null;
        String primaryPhotoUrl = null;

        if (!profileRows.isEmpty()) {
            Map<String, Object> profile = profileRows.get(0);
            displayName = (String) profile.get("display_name");

            Object dob = profile.get("date_of_birth");
            if (dob != null) {
                LocalDate dobDate = toLocalDate(dob);
                if (dobDate != null) {
                    age = Period.between(dobDate, LocalDate.now()).getYears();
                }
            }

            String photoPath = (String) profile.get("primary_photo_path");
            if (photoPath != null) {
                primaryPhotoUrl = storageService.generateSignedUrl("profile-photos", photoPath, 3600);
            }
        }

        return new RevealResponse(actionId, actionType, actorId, displayName, age,
                primaryPhotoUrl, idempotent, creditBalance);
    }

    private LocalDate toLocalDate(Object obj) {
        if (obj instanceof java.sql.Date d) return d.toLocalDate();
        if (obj instanceof OffsetDateTime odt) return odt.toLocalDate();
        if (obj instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        return null;
    }
}
