package com.qaliye.backend.matchmaking.service;

import com.qaliye.backend.matchmaking.MatchmakingConstants;
import com.qaliye.backend.matchmaking.config.MatchmakingProperties;
import com.qaliye.backend.matchmaking.dto.CandidateMatchView;
import com.qaliye.backend.matchmaking.repository.MatchmakingPreferenceRepository;
import com.qaliye.backend.matchmaking.repository.MatchmakingPreferenceRepository.PreferenceRow;
import com.qaliye.backend.matchmaking.repository.MatchmakingPreferenceRepository.PreferenceValues;
import com.qaliye.backend.matchmaking.repository.MatchmakingRequestRepository;
import com.qaliye.backend.matchmaking.repository.MatchmakingRequestRepository.RequestRow;
import com.qaliye.backend.matchmaking.service.MatchmakingCompatibilityService.CompatibilityResult;
import com.qaliye.backend.matchmaking.service.MatchmakingCompatibilityService.ProfileFacts;
import com.qaliye.backend.matchmaking.service.MatchmakingCompatibilityService.SideInput;
import com.qaliye.backend.storage.SupabaseStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Admin-only candidate search for a given matchmaking request.
 *
 * <p>Redesigned per V71: preferences are read LIVE (no snapshot). The SQL
 * applies only the §10 hard exclusions plus any admin overrides as hard
 * filters; every survivor is then scored bidirectionally in Java by
 * {@link MatchmakingCompatibilityService} — requester effective prefs and
 * candidate live prefs are scoring inputs, not WHERE clauses.
 *
 * <p>Results are ranked by {@code overallPercentage} DESC then
 * {@code request_id} ASC and paged from the materialized list.
 */
@Service
public class MatchmakingCandidateSearchService {

    private static final Logger log = LoggerFactory.getLogger(MatchmakingCandidateSearchService.class);

    /**
     * Candidate materialization: hard exclusions only (§10 items 1–10).
     * Requester/candidate preference columns are NOT filtered here — they are
     * scored in Java (design §6 "scored, not filtered"). The {@code :f*}
     * parameters bind ONLY the admin overrides supplied for this search.
     *
     * <p>Returns the candidate's request columns, its full live preference
     * row ({@code mp_*}), and its profile facts ({@code pf_*}) in one pass.
     */
    private static final String CANDIDATE_SEARCH_SQL = """
            SELECT r.id, r.user_id, r.matchmaking_preferences_id, r.status,
                   r.request_charge_idempotency_key, r.contact_phone,
                   r.created_at, r.completed_at, r.cancelled_at, r.expires_at, r.updated_at,
                   mp.gender                     AS mp_gender,
                   mp.min_age                    AS mp_min_age,
                   mp.max_age                    AS mp_max_age,
                   mp.min_height_cm              AS mp_min_height_cm,
                   mp.max_height_cm              AS mp_max_height_cm,
                   mp.specific_country_codes     AS mp_specific_country_codes,
                   mp.has_children_preference    AS mp_has_children_preference,
                   mp.wants_children_preference  AS mp_wants_children_preference,
                   mp.religion_preferences       AS mp_religion_preferences,
                   mp.education_levels           AS mp_education_levels,
                   mp.marital_statuses           AS mp_marital_statuses,
                   mp.smoking_preferences        AS mp_smoking_preferences,
                   mp.drinking_preferences       AS mp_drinking_preferences,
                   mp.language_preference_ids    AS mp_language_preference_ids,
                   mp.ethnicity_preference_ids   AS mp_ethnicity_preference_ids,
                   mp.user_notes                 AS mp_user_notes,
                   mp.marriage_timeline          AS mp_marriage_timeline,
                   mp.long_distance_relationship AS mp_long_distance_relationship,
                   mp.family_involvement         AS mp_family_involvement,
                   mp.religion_important         AS mp_religion_important,
                   mp.willing_to_relocate        AS mp_willing_to_relocate,
                   cp.gender                     AS pf_gender,
                   CASE WHEN cp.date_of_birth IS NOT NULL
                        THEN EXTRACT(YEAR FROM AGE(cp.date_of_birth))::INT END AS pf_age,
                   cp.height_cm                  AS pf_height_cm,
                   ca.country_code               AS pf_country_code,
                   cp.has_children               AS pf_has_children,
                   cp.wants_children             AS pf_wants_children,
                   cp.religion                   AS pf_religion,
                   cp.education_level            AS pf_education_level,
                   cp.marital_status             AS pf_marital_status,
                   cp.smoking                    AS pf_smoking,
                   cp.drinking                   AS pf_drinking,
                   cp.smoking_detail             AS pf_smoking_detail,
                   cp.drinking_detail            AS pf_drinking_detail,
                   cp.language_ids               AS pf_language_ids,
                   cp.ethnicity_ids              AS pf_ethnicity_ids
            FROM matchmaking_requests r
            JOIN matchmaking_preferences mp ON mp.id = r.matchmaking_preferences_id
            JOIN app_users cau ON cau.id = r.user_id
            JOIN profiles  cp  ON cp.user_id = r.user_id
            LEFT JOIN addresses ca ON ca.id = cau.address_id
            WHERE r.status = 'OPEN'
              AND r.id <> :requesterRequestId
              AND r.user_id <> :requesterUserId
              -- (4) candidate account active
              AND cau.status = 'ACTIVE'
              AND cau.deleted_at IS NULL
              -- (5) profile visible: onboarded + visible + approved primary photo
              AND cp.is_onboarded = TRUE
              AND cp.is_visible = TRUE
              AND EXISTS (
                  SELECT 1 FROM profile_photos pp
                  WHERE pp.user_id = r.user_id
                    AND pp.is_primary = TRUE
                    AND pp.moderation_status = 'APPROVED'
                    AND pp.deleted_at IS NULL
              )
              -- (7) no active block either direction
              AND NOT EXISTS (
                  SELECT 1 FROM user_blocks ub
                  WHERE ub.status = 'ACTIVE'
                    AND (
                        (ub.blocker_user_id = r.user_id AND ub.blocked_user_id = :requesterUserId)
                        OR (ub.blocker_user_id = :requesterUserId AND ub.blocked_user_id = r.user_id)
                    )
              )
              -- (6) no active match between the pair
              AND NOT EXISTS (
                  SELECT 1 FROM matches m
                  WHERE m.status = 'ACTIVE'
                    AND (
                        (m.user_one_id = r.user_id AND m.user_two_id = :requesterUserId)
                        OR (m.user_one_id = :requesterUserId AND m.user_two_id = r.user_id)
                    )
              )
              -- (9) re-introduction cooldown, measured from the previous
              --     introduction's terminal timestamp
              AND NOT EXISTS (
                  SELECT 1 FROM matchmaking_introductions i
                  WHERE (
                        (i.requester_request_id = :requesterRequestId
                         AND i.candidate_request_id = r.id)
                     OR (i.requester_request_id = r.id
                         AND i.candidate_request_id = :requesterRequestId)
                  )
                  AND COALESCE(i.completed_at, i.updated_at, i.created_at)
                      >= CURRENT_TIMESTAMP - (INTERVAL '1 day' * :cooldownDays)
              )
              -- (3) gender rule — profile-derived, hetero, always active
              AND (cp.gender IS NOT NULL AND :rqGender::text IS NOT NULL
                   AND cp.gender <> :rqGender)
              -- (10) admin overrides as hard filters (inactive params are inert)
              AND (:fMinAge::int IS NULL OR calculate_age(cp.date_of_birth) >= :fMinAge)
              AND (:fMaxAge::int IS NULL OR calculate_age(cp.date_of_birth) <= :fMaxAge)
              AND (:fMinHeight::int IS NULL OR cp.height_cm >= :fMinHeight)
              AND (:fMaxHeight::int IS NULL OR cp.height_cm <= :fMaxHeight)
              AND (cardinality(:fCountryCodes::text[]) = 0
                   OR ca.country_code = ANY(:fCountryCodes::text[]))
              AND (:fHasChildren = 'any'
                   OR (:fHasChildren = 'yes' AND cp.has_children IS TRUE)
                   OR (:fHasChildren = 'no'  AND cp.has_children IS DISTINCT FROM TRUE))
              AND (:fWantsChildren = 'any'
                   OR (:fWantsChildren = 'yes' AND cp.wants_children IS TRUE)
                   OR (:fWantsChildren = 'no'  AND cp.wants_children IS DISTINCT FROM TRUE)
                   OR (:fWantsChildren = 'not_sure' AND cp.wants_children IS NULL)
                   OR (:fWantsChildren = 'open_to_discussion'
                       AND cp.wants_children IS DISTINCT FROM FALSE))
              AND (cardinality(:fReligions::text[]) = 0
                   OR cp.religion = ANY(:fReligions::text[]))
              AND (cardinality(:fEducation::text[]) = 0
                   OR cp.education_level = ANY(:fEducation::text[]))
              AND (cardinality(:fMarital::text[]) = 0
                   OR cp.marital_status = ANY(:fMarital::text[]))
              AND (cardinality(:fSmoking::text[]) = 0
                   OR COALESCE(cp.smoking_detail,
                               CASE WHEN cp.smoking IS TRUE THEN 'YES'
                                    WHEN cp.smoking IS FALSE THEN 'NO' END)
                      = ANY(:fSmoking::text[]))
              AND (cardinality(:fDrinking::text[]) = 0
                   OR COALESCE(cp.drinking_detail,
                               CASE WHEN cp.drinking IS TRUE THEN 'YES'
                                    WHEN cp.drinking IS FALSE THEN 'NO' END)
                      = ANY(:fDrinking::text[]))
              AND (cardinality(:fLangIds::uuid[]) = 0
                   OR cp.language_ids && :fLangIds::uuid[])
              AND (cardinality(:fEthIds::uuid[]) = 0
                   OR cp.ethnicity_ids && :fEthIds::uuid[])
            ORDER BY r.created_at ASC
            LIMIT :cap
            """;

    /**
     * Batch profile cards for a page of candidates — display fields plus the
     * primary photo's storage path (signed per-row below).
     */
    private static final String CANDIDATE_PROFILE_SQL = """
            SELECT p.user_id, p.display_name, p.gender, p.is_verified,
                   calculate_age(p.date_of_birth) AS age,
                   a.city, a.region, a.country_code, a.country_name,
                   au.last_active_at,
                   ph.storage_bucket AS photo_bucket, ph.storage_path AS photo_path
            FROM profiles p
            LEFT JOIN app_users au ON au.id = p.user_id
            LEFT JOIN addresses a  ON a.id = au.address_id
            LEFT JOIN LATERAL (
                SELECT storage_bucket, storage_path FROM profile_photos
                WHERE user_id = p.user_id AND deleted_at IS NULL
                      AND moderation_status = 'APPROVED'
                ORDER BY is_primary DESC, photo_order ASC
                LIMIT 1
            ) ph ON TRUE
            WHERE p.user_id IN (:userIds)
            """;

    private static final int PHOTO_URL_TTL_SECONDS = 3600;
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** Override keys this service understands (snake_case column names). */
    private static final Set<String> KNOWN_FILTER_KEYS = Set.of(
            "min_age", "max_age", "min_height_cm", "max_height_cm",
            "specific_country_codes", "has_children_preference",
            "wants_children_preference", "religion_preferences",
            "education_levels", "marital_statuses",
            "smoking_preferences", "drinking_preferences",
            "language_preference_ids", "ethnicity_preference_ids");

    /** All-inactive preference set — the base overrides are applied onto. */
    private static final PreferenceValues EMPTY_VALUES = new PreferenceValues(
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null);

    private final NamedParameterJdbcTemplate jdbc;
    private final MatchmakingRequestRepository requestRepo;
    private final MatchmakingPreferenceRepository preferenceRepo;
    private final MatchmakingCompatibilityService compatibilityService;
    private final MatchmakingUserFactsLoader factsLoader;
    private final MatchmakingProperties props;
    private final SupabaseStorageService storageService;

    public MatchmakingCandidateSearchService(NamedParameterJdbcTemplate jdbc,
                                             MatchmakingRequestRepository requestRepo,
                                             MatchmakingPreferenceRepository preferenceRepo,
                                             MatchmakingCompatibilityService compatibilityService,
                                             MatchmakingUserFactsLoader factsLoader,
                                             MatchmakingProperties props,
                                             SupabaseStorageService storageService) {
        this.jdbc = jdbc;
        this.requestRepo = requestRepo;
        this.preferenceRepo = preferenceRepo;
        this.compatibilityService = compatibilityService;
        this.factsLoader = factsLoader;
        this.props = props;
        this.storageService = storageService;
    }

    /** A materialized candidate: request row + live prefs + profile facts + score. */
    private record CandidateScoringRow(
            RequestRow request,
            PreferenceValues prefs,
            ProfileFacts facts,
            CompatibilityResult score) {

        CandidateScoringRow withScore(CompatibilityResult s) {
            return new CandidateScoringRow(request, prefs, facts, s);
        }
    }

    /**
     * Scored candidate search. SQL materializes eligible candidates (hard
     * exclusions + admin overrides); each survivor is scored bidirectionally
     * in Java, ranked by {@code overallPercentage} DESC then request id, and
     * paged from the materialized list.
     *
     * @param requesterRequestId  the admin-selected requester request
     * @param adminOverrideParams optional admin overrides keyed by column names
     *                            (e.g. {@code min_age}); unknown keys are rejected
     * @param page                zero-based page into the ranked list
     * @param size                page size (falls back to 20 when non-positive)
     * @return ranked page of candidates, each carrying request row, profile
     *         card, live preferences, and the bidirectional match score
     */
    @Transactional(readOnly = true)
    public List<CandidateMatchView> searchCandidates(UUID requesterRequestId,
                                                     Map<String, Object> adminOverrideParams,
                                                     int page, int size) {
        RequestRow requesterRequest = requestRepo.findById(requesterRequestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Requester request not found."));

        if (!MatchmakingConstants.REQUEST_OPEN.equals(requesterRequest.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Requester request is not OPEN.");
        }

        if (adminOverrideParams != null) {
            for (String key : adminOverrideParams.keySet()) {
                if (!KNOWN_FILTER_KEYS.contains(key)) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Unknown override parameter: " + key);
                }
            }
        }

        // Live requester prefs — the request tracks the user's current row.
        PreferenceRow requesterPrefs = preferenceRepo
                .findById(requesterRequest.matchmakingPreferencesId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Requester matchmaking preferences not found."));

        MatchmakingUserFactsLoader.FactRow facts = factsLoader.load(requesterRequest.userId());

        // Effective prefs for scoring: live values + admin overrides merged.
        PreferenceValues effective = MatchmakingPreferenceNormalizer.normalize(
                applyOverrides(MatchmakingCompatibilityService.toValues(requesterPrefs),
                        adminOverrideParams));
        // Overrides only — bound to the SQL hard filters (:f* params).
        PreferenceValues overridesOnly = MatchmakingPreferenceNormalizer.normalize(
                applyOverrides(EMPTY_VALUES, adminOverrideParams));

        var params = new MapSqlParameterSource()
                .addValue("requesterRequestId", requesterRequestId)
                .addValue("requesterUserId", requesterRequest.userId())
                .addValue("cap", props.getCandidateSearchCap())
                .addValue("cooldownDays", props.getReintroductionCooldownDays())
                .addValue("rqGender", facts.gender())
                // Admin-override hard filters (NULL / empty / 'any' → inert)
                .addValue("fMinAge", overridesOnly.minAge())
                .addValue("fMaxAge", overridesOnly.maxAge())
                .addValue("fMinHeight", overridesOnly.minHeightCm())
                .addValue("fMaxHeight", overridesOnly.maxHeightCm())
                .addValue("fCountryCodes", arrayLiteral(overridesOnly.specificCountryCodes()))
                .addValue("fHasChildren", overridesOnly.hasChildrenPreference())
                .addValue("fWantsChildren", overridesOnly.wantsChildrenPreference())
                .addValue("fReligions", arrayLiteral(overridesOnly.religionPreferences()))
                .addValue("fEducation", arrayLiteral(overridesOnly.educationLevels()))
                .addValue("fMarital", arrayLiteral(overridesOnly.maritalStatuses()))
                .addValue("fSmoking", arrayLiteral(overridesOnly.smokingPreferences()))
                .addValue("fDrinking", arrayLiteral(overridesOnly.drinkingPreferences()))
                .addValue("fLangIds", uuidArrayLiteral(overridesOnly.languagePreferenceIds()))
                .addValue("fEthIds", uuidArrayLiteral(overridesOnly.ethnicityPreferenceIds()));

        SideInput requesterSide = MatchmakingCompatibilityService.side(
                effective, facts.toProfileFacts());

        List<CandidateScoringRow> scored = jdbc
                .query(CANDIDATE_SEARCH_SQL, params, this::mapCandidate)
                .stream()
                .map(c -> c.withScore(compatibilityService.score(
                        requesterSide,
                        MatchmakingCompatibilityService.side(c.prefs(), c.facts()))))
                .sorted(Comparator
                        .comparingInt((CandidateScoringRow c) -> c.score().overallPercentage())
                        .reversed()
                        .thenComparing(c -> c.request().id()))
                .toList();

        // Page from the ranked, materialized list.
        int pageSize = size > 0 ? size : DEFAULT_PAGE_SIZE;
        int from = Math.min(Math.max(page, 0) * pageSize, scored.size());
        int to = Math.min(from + pageSize, scored.size());
        List<CandidateScoringRow> window = scored.subList(from, to);
        if (window.isEmpty()) return List.of();

        Map<UUID, CandidateMatchView.CandidateProfileCard> cards = fetchProfileCards(
                window.stream().map(c -> c.request().userId()).toList());
        return window.stream()
                .map(c -> new CandidateMatchView(
                        c.request(),
                        cards.get(c.request().userId()),
                        c.prefs(),
                        new CandidateMatchView.MatchScore(
                                c.score().forwardPercentage(),
                                c.score().reversePercentage(),
                                c.score().overallPercentage(),
                                c.score().details())))
                .toList();
    }

    /**
     * One query for all profile cards on the page, then a signed URL per
     * primary photo. Candidates without a profile still appear (null card) —
     * the matchmaker sees the request row regardless.
     */
    private Map<UUID, CandidateMatchView.CandidateProfileCard> fetchProfileCards(
            List<UUID> userIds) {
        Map<UUID, CandidateMatchView.CandidateProfileCard> cards = new HashMap<>();
        jdbc.query(CANDIDATE_PROFILE_SQL, Map.of("userIds", userIds), (rs, i) -> {
            String bucket = rs.getString("photo_bucket");
            String path = rs.getString("photo_path");
            String photoUrl = (bucket != null && path != null)
                    ? storageService.generateSignedUrl(bucket, path, PHOTO_URL_TTL_SECONDS)
                    : null;
            return new CandidateMatchView.CandidateProfileCard(
                    rs.getObject("user_id", UUID.class),
                    rs.getString("display_name"),
                    rs.getObject("age", Integer.class),
                    rs.getString("gender"),
                    rs.getString("city"),
                    rs.getString("region"),
                    rs.getString("country_code"),
                    rs.getString("country_name"),
                    rs.getObject("is_verified", Boolean.class),
                    photoUrl,
                    rs.getObject("last_active_at", java.time.OffsetDateTime.class));
        }).forEach(card -> cards.put(card.userId(), card));
        return cards;
    }

    // ── Mappers ──────────────────────────────────────────────────────────

    private CandidateScoringRow mapCandidate(ResultSet rs, int i) throws SQLException {
        RequestRow request = new RequestRow(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("matchmaking_preferences_id", UUID.class),
                rs.getString("status"),
                rs.getObject("request_charge_idempotency_key", UUID.class),
                rs.getString("contact_phone"),
                rs.getObject("created_at", java.time.OffsetDateTime.class),
                rs.getObject("completed_at", java.time.OffsetDateTime.class),
                rs.getObject("cancelled_at", java.time.OffsetDateTime.class),
                rs.getObject("expires_at", java.time.OffsetDateTime.class),
                rs.getObject("updated_at", java.time.OffsetDateTime.class),
                null);

        PreferenceValues prefs = new PreferenceValues(
                rs.getObject("mp_min_age", Integer.class),
                rs.getObject("mp_max_age", Integer.class),
                rs.getObject("mp_min_height_cm", Integer.class),
                rs.getObject("mp_max_height_cm", Integer.class),
                toStringArray(rs.getArray("mp_specific_country_codes")),
                rs.getString("mp_has_children_preference"),
                rs.getString("mp_wants_children_preference"),
                toStringArray(rs.getArray("mp_religion_preferences")),
                toStringArray(rs.getArray("mp_education_levels")),
                toStringArray(rs.getArray("mp_marital_statuses")),
                toStringArray(rs.getArray("mp_smoking_preferences")),
                toStringArray(rs.getArray("mp_drinking_preferences")),
                toUuidArray(rs.getArray("mp_language_preference_ids")),
                toUuidArray(rs.getArray("mp_ethnicity_preference_ids")),
                rs.getString("mp_user_notes"),
                rs.getString("mp_marriage_timeline"),
                rs.getString("mp_long_distance_relationship"),
                rs.getString("mp_family_involvement"),
                rs.getString("mp_religion_important"),
                rs.getString("mp_willing_to_relocate"));

        ProfileFacts profileFacts = new ProfileFacts(
                rs.getObject("pf_age", Integer.class),
                rs.getObject("pf_height_cm", Integer.class),
                rs.getString("pf_country_code"),
                rs.getObject("pf_has_children", Boolean.class),
                rs.getObject("pf_wants_children", Boolean.class),
                rs.getString("pf_religion"),
                rs.getString("pf_education_level"),
                rs.getString("pf_marital_status"),
                detailOrBool(rs.getString("pf_smoking_detail"),
                        rs.getObject("pf_smoking", Boolean.class)),
                detailOrBool(rs.getString("pf_drinking_detail"),
                        rs.getObject("pf_drinking", Boolean.class)),
                toUuidArray(rs.getArray("pf_language_ids")),
                toUuidArray(rs.getArray("pf_ethnicity_ids")));

        return new CandidateScoringRow(request, prefs, profileFacts, null);
    }

    // ── Private helpers ──────────────────────────────────────────────────

    /** Applies supplied override keys over a base preference set. */
    private PreferenceValues applyOverrides(PreferenceValues v, Map<String, Object> o) {
        if (o == null || o.isEmpty()) return v;
        return new PreferenceValues(
                o.containsKey("min_age") ? asInt(o.get("min_age")) : v.minAge(),
                o.containsKey("max_age") ? asInt(o.get("max_age")) : v.maxAge(),
                o.containsKey("min_height_cm") ? asInt(o.get("min_height_cm")) : v.minHeightCm(),
                o.containsKey("max_height_cm") ? asInt(o.get("max_height_cm")) : v.maxHeightCm(),
                o.containsKey("specific_country_codes")
                        ? asStringArray(o.get("specific_country_codes")) : v.specificCountryCodes(),
                o.containsKey("has_children_preference")
                        ? asString(o.get("has_children_preference")) : v.hasChildrenPreference(),
                o.containsKey("wants_children_preference")
                        ? asString(o.get("wants_children_preference")) : v.wantsChildrenPreference(),
                o.containsKey("religion_preferences")
                        ? asStringArray(o.get("religion_preferences")) : v.religionPreferences(),
                o.containsKey("education_levels")
                        ? asStringArray(o.get("education_levels")) : v.educationLevels(),
                o.containsKey("marital_statuses")
                        ? asStringArray(o.get("marital_statuses")) : v.maritalStatuses(),
                o.containsKey("smoking_preferences")
                        ? asStringArray(o.get("smoking_preferences")) : v.smokingPreferences(),
                o.containsKey("drinking_preferences")
                        ? asStringArray(o.get("drinking_preferences")) : v.drinkingPreferences(),
                o.containsKey("language_preference_ids")
                        ? asUuidArray(o.get("language_preference_ids")) : v.languagePreferenceIds(),
                o.containsKey("ethnicity_preference_ids")
                        ? asUuidArray(o.get("ethnicity_preference_ids")) : v.ethnicityPreferenceIds(),
                v.userNotes(), v.marriageTimeline(), v.longDistanceRelationship(),
                v.familyInvolvement(), v.religionImportant(), v.willingToRelocate());
    }

    /** Falls back from the *_detail enum to the boolean flag ('YES'/'NO'). */
    private static String detailOrBool(String detail, Boolean flag) {
        if (detail != null) return detail;
        if (flag == null) return null;
        return flag ? "YES" : "NO";
    }

    private static Integer asInt(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }

    private static String asString(Object v) {
        return v instanceof String s && !s.isBlank() ? s : null;
    }

    private static String[] asStringArray(Object v) {
        if (v instanceof String[] a) return a;
        if (v instanceof java.util.Collection<?> c) {
            return c.stream().filter(String.class::isInstance)
                    .map(String.class::cast).toArray(String[]::new);
        }
        return null;
    }

    private static UUID[] asUuidArray(Object v) {
        if (v instanceof UUID[] a) return a;
        if (v instanceof java.util.Collection<?> c) {
            try {
                return c.stream()
                        .map(e -> e instanceof UUID u ? u : UUID.fromString(String.valueOf(e)))
                        .toArray(UUID[]::new);
            } catch (IllegalArgumentException ex) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Invalid UUID in preference list: " + ex.getMessage());
            }
        }
        return null;
    }

    /** Postgres array literal, e.g. {@code {GB,US}} — empty/null yields {@code {}}. */
    private static String arrayLiteral(String[] values) {
        return "{" + String.join(",", values == null ? new String[0] : values) + "}";
    }

    private static String uuidArrayLiteral(UUID[] values) {
        if (values == null) return "{}";
        return "{" + Arrays.stream(values).map(UUID::toString)
                .reduce((a, b) -> a + "," + b).orElse("") + "}";
    }

    private static String[] toStringArray(java.sql.Array arr) throws SQLException {
        if (arr == null) return new String[0];
        return (String[]) arr.getArray();
    }

    private static UUID[] toUuidArray(java.sql.Array arr) throws SQLException {
        if (arr == null) return new UUID[0];
        return (UUID[]) arr.getArray();
    }
}
