package com.qaliye.backend.profile;

import com.qaliye.backend.activity.ActivityStatus;
import com.qaliye.backend.activity.ActivityStatusService;
import com.qaliye.backend.catalog.CatalogService;
import com.qaliye.backend.catalog.EthnicityOption;
import com.qaliye.backend.catalog.LanguageOption;
import com.qaliye.backend.discovery.dto.UpdateDiscoveryPreferencesRequest;
import com.qaliye.backend.profile.dto.CulturalDetailsRequest;
import com.qaliye.backend.profile.dto.DiscoveryPreferencesDto;
import com.qaliye.backend.profile.dto.OtherUserProfileDto;
import com.qaliye.backend.profile.dto.ProfileAddressDto;
import com.qaliye.backend.profile.dto.ProfileLocationDto;
import com.qaliye.backend.profile.dto.ProfileMeDto;
import com.qaliye.backend.profile.dto.ProfilePhotoDto;
import com.qaliye.backend.profile.dto.ProfilePhotosResponse;
import com.qaliye.backend.profile.dto.ProfileUpdateRequest;
import com.qaliye.backend.profile.dto.VisibilityUpdateResponse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ProfileService {

    private static final String PREFERENCES_SELECT_SQL = """
            SELECT interested_in_gender, min_age, max_age, max_distance_km,
                   show_verified_only,
                   location_mode, specific_country_codes, expand_search_when_limited,
                   has_children_preference, wants_children_preference,
                   religion_preferences, language_preference_ids, ethnicity_preference_ids,
                   preferences_version
            FROM discovery_preferences WHERE user_id = :userId
            """;

    private static final Set<String> VALID_MARITAL_STATUS =
            Set.of("NEVER_MARRIED", "DIVORCED", "WIDOWED", "SEPARATED");
    private static final Set<String> VALID_EDUCATION_LEVEL =
            Set.of("HIGH_SCHOOL", "DIPLOMA", "BACHELORS", "MASTERS", "DOCTORATE", "OTHER");
    private static final Set<String> VALID_RELIGION =
            Set.of("ORTHODOX_CHRISTIAN", "PROTESTANT", "CATHOLIC", "MUSLIM",
                   "TRADITIONAL", "OTHER", "PREFER_NOT_TO_SAY");
    private static final Set<String> VALID_RESIDENCY_TYPES = Set.of("ETHIOPIA", "ERITREA", "DIASPORA");
    private static final Set<String> VALID_GENDERS = Set.of("MALE", "FEMALE");
    private static final Set<String> VALID_RELATIONSHIP_INTENTION =
            Set.of("MARRIAGE", "SERIOUS_RELATIONSHIP", "LONG_TERM", "FRIENDSHIP", "NOT_SURE_YET");
    private static final Set<String> VALID_SMOKING_DETAIL =
            Set.of("NO", "YES", "OCCASIONALLY", "TRYING_TO_QUIT");
    private static final Set<String> VALID_DRINKING_DETAIL =
            Set.of("NO", "SOCIALLY", "OCCASIONALLY", "YES");

    private final NamedParameterJdbcTemplate jdbc;
    private final ProfilePhotoService profilePhotoService;
    private final ActivityStatusService activityStatusService;
    private final CatalogService catalogService;

    public ProfileService(NamedParameterJdbcTemplate jdbc,
                          ProfilePhotoService profilePhotoService,
                          ActivityStatusService activityStatusService,
                          CatalogService catalogService) {
        this.jdbc = jdbc;
        this.profilePhotoService = profilePhotoService;
        this.activityStatusService = activityStatusService;
        this.catalogService = catalogService;
    }

    public ProfileMeDto getCurrentProfile(UUID userId) {
        checkActorEligibility(userId);

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT
                    p.user_id, p.display_name, p.gender, p.date_of_birth,
                    calculate_age(p.date_of_birth) AS age,
                    p.bio, p.height_cm, p.residency_type,
                    p.ethnicity_ids, p.ethnicity_other_text,
                    p.nationality, p.religion, p.education_level, p.occupation,
                    p.relationship_intention, p.marital_status,
                    p.has_children, p.wants_children,
                    p.smoking, p.drinking, p.smoking_detail, p.drinking_detail,
                    p.activity_level, p.interests, p.language_ids,
                    p.discovery_mode, p.is_visible, p.is_onboarded, p.is_verified, p.profile_completion_score,
                    a.id AS address_id, a.city, a.region, a.country_code, a.country_name,
                    a.formatted_address, a.location_source,
                    au.role
                FROM profiles p
                JOIN app_users au ON au.id = p.user_id
                LEFT JOIN addresses a ON a.id = au.address_id
                WHERE p.user_id = :userId
                """, Map.of("userId", userId));

        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND");
        }

        Map<String, Object> r = rows.get(0);

        List<EthnicityOption> ethnicities = catalogService.expandEthnicityIds(
                toUuidArray(r.get("ethnicity_ids")));
        List<LanguageOption> languages = catalogService.expandLanguageIds(
                toUuidArray(r.get("language_ids")));

        DiscoveryPreferencesDto prefs = loadPreferences(userId);
        ProfilePhotosResponse photosResp = profilePhotoService.getPhotos(userId);
        List<ProfilePhotoDto> photos = photosResp.photos();
        String primaryPhotoUrl = photos.stream()
                .filter(ph -> Boolean.TRUE.equals(ph.isPrimary()))
                .map(ProfilePhotoDto::signedUrl)
                .findFirst().orElse(null);

        return new ProfileMeDto(
                (UUID) r.get("user_id"),
                (String) r.get("display_name"),
                r.get("age") != null ? ((Number) r.get("age")).intValue() : null,
                (String) r.get("gender"),
                r.get("date_of_birth") instanceof java.sql.Date d ? d.toLocalDate()
                        : r.get("date_of_birth") instanceof LocalDate ld ? ld : null,
                (String) r.get("bio"),
                r.get("height_cm") != null ? ((Number) r.get("height_cm")).intValue() : null,
                (String) r.get("residency_type"),
                buildAddressDto(r),
                ethnicities,
                (String) r.get("ethnicity_other_text"),
                (String) r.get("nationality"),
                (String) r.get("religion"),
                (String) r.get("education_level"),
                (String) r.get("occupation"),
                (String) r.get("relationship_intention"),
                (String) r.get("marital_status"),
                (Boolean) r.get("has_children"),
                (Boolean) r.get("wants_children"),
                (Boolean) r.get("smoking"),
                (Boolean) r.get("drinking"),
                (String) r.get("smoking_detail"),
                (String) r.get("drinking_detail"),
                (String) r.get("activity_level"),
                toStringList(r.get("interests")),
                languages,
                (Boolean) r.get("is_visible"),
                (String) r.get("discovery_mode"),
                (Boolean) r.get("is_onboarded"),
                (Boolean) r.get("is_verified"),
                r.get("profile_completion_score") != null
                        ? ((Number) r.get("profile_completion_score")).intValue() : 0,
                prefs,
                primaryPhotoUrl,
                photos,
                (String) r.get("role")
        );
    }

    @Transactional
    public ProfileMeDto updateProfile(UUID userId, ProfileUpdateRequest request) {
        checkActorEligibility(userId);

        ensureProfileExists(userId, request);

        if (request.dateOfBirth() != null) {
            LocalDate dob = request.dateOfBirth();
            if (dob.isAfter(LocalDate.now().minusYears(18))) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR");
            }
            if (dob.isBefore(LocalDate.now().minusYears(120))) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR");
            }
        }

        validateOptionalEnum("marital_status", request.maritalStatus(), VALID_MARITAL_STATUS);
        validateOptionalEnum("education_level", request.educationLevel(), VALID_EDUCATION_LEVEL);
        validateOptionalEnum("religion", request.religion(), VALID_RELIGION);

        Boolean smoking = request.smoking();
        Boolean drinking = request.drinking();
        String smokingDetail = request.smokingDetail();
        String drinkingDetail = request.drinkingDetail();

        if (smokingDetail != null) {
            smoking = !"NO".equals(smokingDetail);
        }
        if (drinkingDetail != null) {
            drinking = !"NO".equals(drinkingDetail);
        }

        String[] interestsArray = request.interests() != null
                ? request.interests().toArray(String[]::new) : null;

        boolean updateEthnicities = request.ethnicityIds() != null;
        boolean updateEthOtherText = request.ethnicityOtherText() != null;
        boolean updateLanguages = request.languageIds() != null;

        if (updateEthnicities) {
            catalogService.validateEthnicityIds(request.ethnicityIds());
        }
        if (updateLanguages) {
            catalogService.validateLanguageIds(request.languageIds());
        }

        String ethArray = updateEthnicities
                ? CatalogService.buildUuidArrayParam(request.ethnicityIds()) : null;
        String langArray = updateLanguages
                ? CatalogService.buildUuidArrayParam(request.languageIds()) : null;
        String ethnicityOtherText = updateEthOtherText
                ? (request.ethnicityOtherText().isBlank() ? null : request.ethnicityOtherText().trim()) : null;

        int newScore = profilePhotoService.computeScore(userId);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("displayName", trimOrNull(request.displayName()))
                .addValue("gender", request.gender())
                .addValue("dateOfBirth", request.dateOfBirth())
                .addValue("heightCm", request.heightCm())
                .addValue("bio", request.bio())
                .addValue("nationality", request.nationality())
                .addValue("religion", request.religion())
                .addValue("educationLevel", request.educationLevel())
                .addValue("occupation", trimOrNull(request.occupation()))
                .addValue("relationshipIntention", request.relationshipIntention())
                .addValue("maritalStatus", request.maritalStatus())
                .addValue("hasChildren", request.hasChildren())
                .addValue("wantsChildren", request.wantsChildren())
                .addValue("smoking", smoking)
                .addValue("drinking", drinking)
                .addValue("smokingDetail", smokingDetail)
                .addValue("drinkingDetail", drinkingDetail)
                .addValue("activityLevel", request.activityLevel())
                .addValue("interests", interestsArray)
                .addValue("discoveryMode", request.discoveryMode())
                .addValue("updateEthnicities", updateEthnicities)
                .addValue("ethIds", ethArray)
                .addValue("updateEthOtherText", updateEthOtherText)
                .addValue("otherText", ethnicityOtherText)
                .addValue("updateLanguages", updateLanguages)
                .addValue("langIds", langArray)
                .addValue("score", newScore);

        jdbc.update("""
                UPDATE profiles SET
                    display_name           = COALESCE(:displayName, display_name),
                    gender                 = COALESCE(:gender, gender),
                    date_of_birth          = COALESCE(:dateOfBirth, date_of_birth),
                    height_cm              = COALESCE(:heightCm, height_cm),
                    bio                    = COALESCE(:bio, bio),
                    nationality            = COALESCE(:nationality, nationality),
                    religion               = COALESCE(:religion, religion),
                    education_level        = COALESCE(:educationLevel, education_level),
                    occupation             = COALESCE(:occupation, occupation),
                    relationship_intention = COALESCE(:relationshipIntention, relationship_intention),
                    marital_status         = COALESCE(:maritalStatus, marital_status),
                    has_children           = COALESCE(:hasChildren, has_children),
                    wants_children         = COALESCE(:wantsChildren, wants_children),
                    smoking                = COALESCE(:smoking, smoking),
                    drinking               = COALESCE(:drinking, drinking),
                    smoking_detail         = COALESCE(:smokingDetail, smoking_detail),
                    drinking_detail        = COALESCE(:drinkingDetail, drinking_detail),
                    activity_level         = COALESCE(:activityLevel, activity_level),
                    interests              = COALESCE(:interests, interests),
                    discovery_mode         = COALESCE(:discoveryMode, discovery_mode),
                    ethnicity_ids          = CASE WHEN :updateEthnicities THEN :ethIds::UUID[] ELSE ethnicity_ids END,
                    ethnicity_other_text   = CASE WHEN :updateEthOtherText THEN :otherText ELSE ethnicity_other_text END,
                    language_ids           = CASE WHEN :updateLanguages THEN :langIds::UUID[] ELSE language_ids END,
                    profile_completion_score = :score
                WHERE user_id = :userId
                """, params);

        int finalScore = profilePhotoService.computeScore(userId);
        if (finalScore != newScore) {
            jdbc.update("UPDATE profiles SET profile_completion_score = :score WHERE user_id = :userId",
                    Map.of("score", finalScore, "userId", userId));
        }

        return getCurrentProfile(userId);
    }

    public DiscoveryPreferencesDto getPreferences(UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(PREFERENCES_SELECT_SQL,
                Map.of("userId", userId));
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PREFERENCES_NOT_FOUND");
        }
        return mapToPreferencesDto(rows.get(0));
    }

    @Transactional
    public DiscoveryPreferencesDto updatePreferences(UUID userId, UpdateDiscoveryPreferencesRequest request) {
        if (request.interestedInGender() == null || !VALID_GENDERS.contains(request.interestedInGender())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR");
        }
        if (request.minAge() == null || request.minAge() < 18) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR");
        }
        if (request.maxAge() != null && (request.maxAge() < 18 || request.minAge() > request.maxAge())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR");
        }
        if (request.maxDistanceKm() != null && request.maxDistanceKm() < 1) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR");
        }

        List<UUID> langPrefIds = request.languagePreferenceIds() != null
                ? request.languagePreferenceIds() : Collections.emptyList();
        List<UUID> ethPrefIds = request.ethnicityPreferenceIds() != null
                ? request.ethnicityPreferenceIds() : Collections.emptyList();

        catalogService.validateLanguageIds(langPrefIds);
        catalogService.validateEthnicityIds(ethPrefIds);

        String locationMode = request.locationMode() != null ? request.locationMode() : "anywhere";
        String hasChildrenPref = request.hasChildrenPreference() != null ? request.hasChildrenPreference() : "any";
        String wantsChildrenPref = request.wantsChildrenPreference() != null ? request.wantsChildrenPreference() : "any";

        String countryCodes = request.specificCountryCodes() != null && !request.specificCountryCodes().isEmpty()
                ? "{" + String.join(",", request.specificCountryCodes()) + "}"
                : null;
        String religionPrefs = request.religionPreferences() != null && !request.religionPreferences().isEmpty()
                ? "{" + String.join(",", request.religionPreferences()) + "}"
                : null;
        String langArray = CatalogService.buildUuidArrayParam(langPrefIds);
        String ethArray  = CatalogService.buildUuidArrayParam(ethPrefIds);

        jdbc.update("""
                INSERT INTO discovery_preferences
                    (user_id, interested_in_gender, min_age, max_age, max_distance_km,
                     show_verified_only,
                     location_mode, specific_country_codes, expand_search_when_limited,
                     has_children_preference, wants_children_preference,
                     religion_preferences, language_preference_ids, ethnicity_preference_ids,
                     preferences_version)
                VALUES (:userId, :gender, :minAge, :maxAge, :maxDist,
                        :showVerifiedOnly,
                        :locationMode, :countryCodes::text[], :expandSearch,
                        :hasChildrenPref, :wantsChildrenPref,
                        :religionPrefs::text[], :langIds::UUID[], :ethIds::UUID[],
                        1)
                ON CONFLICT (user_id) DO UPDATE SET
                    interested_in_gender       = EXCLUDED.interested_in_gender,
                    min_age                    = EXCLUDED.min_age,
                    max_age                    = EXCLUDED.max_age,
                    max_distance_km            = EXCLUDED.max_distance_km,
                    show_verified_only         = EXCLUDED.show_verified_only,
                    location_mode              = EXCLUDED.location_mode,
                    specific_country_codes     = EXCLUDED.specific_country_codes,
                    expand_search_when_limited = EXCLUDED.expand_search_when_limited,
                    has_children_preference    = EXCLUDED.has_children_preference,
                    wants_children_preference  = EXCLUDED.wants_children_preference,
                    religion_preferences       = EXCLUDED.religion_preferences,
                    language_preference_ids    = EXCLUDED.language_preference_ids,
                    ethnicity_preference_ids   = EXCLUDED.ethnicity_preference_ids,
                    preferences_version        = discovery_preferences.preferences_version + 1,
                    updated_at                 = NOW()
                """,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("gender", request.interestedInGender())
                        .addValue("minAge", request.minAge())
                        .addValue("maxAge", request.maxAge())
                        .addValue("maxDist", request.maxDistanceKm())
                        .addValue("showVerifiedOnly", Boolean.TRUE.equals(request.showVerifiedOnly()))
                        .addValue("locationMode", locationMode)
                        .addValue("countryCodes", countryCodes)
                        .addValue("expandSearch", Boolean.TRUE.equals(request.expandSearchWhenLimited()))
                        .addValue("hasChildrenPref", hasChildrenPref)
                        .addValue("wantsChildrenPref", wantsChildrenPref)
                        .addValue("religionPrefs", religionPrefs)
                        .addValue("langIds", langArray)
                        .addValue("ethIds", ethArray));

        int score = profilePhotoService.computeScore(userId);
        jdbc.update("UPDATE profiles SET profile_completion_score = :score WHERE user_id = :userId",
                Map.of("score", score, "userId", userId));

        return getPreferences(userId);
    }

    public ProfileLocationDto getLocation(UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.id, a.location_source, a.city, a.region, a.country_code, a.country_name,
                       a.formatted_address, a.location_precision, a.location_place_id
                FROM addresses a
                JOIN app_users u ON u.address_id = a.id
                WHERE u.id = :userId
                """, Map.of("userId", userId));

        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ADDRESS_NOT_FOUND");
        }

        Map<String, Object> row = rows.get(0);
        String source = (String) row.get("location_source");
        String city = (String) row.get("city");
        String region = (String) row.get("region");
        String countryName = (String) row.get("country_name");
        String formatted = (String) row.get("formatted_address");
        String placeId = row.get("location_place_id") != null ? row.get("location_place_id").toString() : null;
        String precision = (String) row.get("location_precision");

        String displayName = "MANUAL".equals(source)
                ? formatted
                : buildDisplayName(city, region, countryName);

        return new ProfileLocationDto(source, displayName, city, region,
                (String) row.get("country_code"), countryName,
                formatted, placeId, precision);
    }

    @Transactional
    public ProfileLocationDto setLocation(UUID userId, SetLocationRequest request) {
        String source = request.locationSource();
        if (source == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_LOCATION");
        }

        if ("GPS".equalsIgnoreCase(source)) {
            setLocationByGps(userId, request);
        } else if ("MANUAL".equalsIgnoreCase(source)) {
            if (request.placeId() == null) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "ADDRESS_NOT_FOUND");
            }
            setLocationByPlace(userId, request.placeId());
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_LOCATION");
        }

        int score = profilePhotoService.computeScore(userId);
        jdbc.update("UPDATE profiles SET profile_completion_score = :score WHERE user_id = :userId",
                Map.of("score", score, "userId", userId));

        return getLocation(userId);
    }

    private void setLocationByPlace(UUID userId, UUID placeId) {
        List<Map<String, Object>> places = jdbc.queryForList(
                "SELECT id, country_code FROM location_places WHERE id = :placeId AND is_active = TRUE",
                Map.of("placeId", placeId));
        if (places.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "ADDRESS_NOT_FOUND");
        }
        String countryCode = (String) places.get(0).get("country_code");

        UUID existingAddressId = fetchExistingAddressId(userId);
        if (existingAddressId != null) {
            jdbc.update("""
                    UPDATE addresses
                    SET location_place_id   = lp.id,
                        country_code        = lp.country_code,
                        country_name        = lp.country_name,
                        city                = lp.city,
                        region              = lp.region,
                        coords              = lp.coords,
                        formatted_address   = lp.display_name,
                        location_source     = 'MANUAL',
                        location_precision  = lp.location_precision,
                        location_updated_at = NOW(),
                        updated_at          = NOW()
                    FROM location_places lp
                    WHERE lp.id = :placeId AND addresses.id = :addressId
                    """, Map.of("placeId", placeId, "addressId", existingAddressId));
        } else {
            List<UUID> newIds = jdbc.query("""
                    INSERT INTO addresses (location_place_id, country_code, country_name, city, region, coords,
                                          formatted_address, location_source, location_precision)
                    SELECT lp.id, lp.country_code, lp.country_name, lp.city, lp.region, lp.coords,
                           lp.display_name, 'MANUAL', lp.location_precision
                    FROM location_places lp WHERE lp.id = :placeId
                    RETURNING id
                    """, Map.of("placeId", placeId), (rs, rowNum) -> rs.getObject("id", UUID.class));
            if (!newIds.isEmpty()) {
                jdbc.update("UPDATE app_users SET address_id = :addressId WHERE id = :userId",
                        Map.of("addressId", newIds.get(0), "userId", userId));
            }
        }
        inferAndPersistResidencyType(userId, countryCode);
    }

    private void setLocationByGps(UUID userId, SetLocationRequest req) {
        if (req.latitude() == null || req.longitude() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_LOCATION");
        }
        double lat = req.latitude(), lng = req.longitude();
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_LOCATION");
        }

        if (req.countryCode() == null || req.countryCode().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_COUNTRY_CODE");
        }
        String countryCode = req.countryCode().trim().toUpperCase();
        if (countryCode.length() > 2) countryCode = countryCode.substring(0, 2);
        if (countryCode.length() != 2 || !countryCode.chars().allMatch(Character::isLetter)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_COUNTRY_CODE");
        }

        String city = trimOrFallback(req.city(), "GPS Location");
        String countryName = trimOrFallback(req.countryName(), "Unknown");
        String region = req.region() != null ? req.region().trim() : null;
        String formatted = req.formattedAddress() != null ? req.formattedAddress().trim() : null;

        UUID existingAddressId = fetchExistingAddressId(userId);
        if (existingAddressId != null) {
            jdbc.update("""
                    UPDATE addresses
                    SET location_place_id   = NULL,
                        country_code        = :cc,
                        country_name        = :cn,
                        city                = :city,
                        region              = :region,
                        formatted_address   = :formatted,
                        coords              = ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                        location_source     = 'GPS',
                        location_precision  = 'EXACT',
                        location_updated_at = NOW(),
                        updated_at          = NOW()
                    WHERE id = :addressId
                    """, Map.of("lat", lat, "lng", lng, "cc", countryCode, "cn", countryName,
                    "city", city, "region", region, "formatted", formatted,
                    "addressId", existingAddressId));
        } else {
            List<UUID> newIds = jdbc.query("""
                    INSERT INTO addresses (location_place_id, country_code, country_name, city, region,
                                          formatted_address, coords, location_source, location_precision)
                    VALUES (NULL, :cc, :cn, :city, :region, :formatted,
                            ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, 'GPS', 'EXACT')
                    RETURNING id
                    """, Map.of("lat", lat, "lng", lng, "cc", countryCode, "cn", countryName,
                    "city", city, "region", region, "formatted", formatted),
                    (rs, rowNum) -> rs.getObject("id", UUID.class));
            if (!newIds.isEmpty()) {
                jdbc.update("UPDATE app_users SET address_id = :addressId WHERE id = :userId",
                        Map.of("addressId", newIds.get(0), "userId", userId));
            }
        }
        inferAndPersistResidencyType(userId, countryCode);
    }

    static String inferResidencyType(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) return "DIASPORA";
        return switch (countryCode.trim().toUpperCase()) {
            case "ET" -> "ETHIOPIA";
            case "ER" -> "ERITREA";
            default   -> "DIASPORA";
        };
    }

    private void inferAndPersistResidencyType(UUID userId, String countryCode) {
        String residencyType = inferResidencyType(countryCode);
        jdbc.update("UPDATE profiles SET residency_type = :rt WHERE user_id = :userId",
                Map.of("rt", residencyType, "userId", userId));
    }

    private void checkActorEligibility(UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, deleted_at FROM app_users WHERE id = :userId",
                Map.of("userId", userId));
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCOUNT_SUSPENDED");
        }
        Map<String, Object> user = rows.get(0);
        if (user.get("deleted_at") != null || !"ACTIVE".equals(user.get("status"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCOUNT_SUSPENDED");
        }
    }

    private void validateOptionalEnum(String field, String value, Set<String> valid) {
        if (value != null && !valid.contains(value)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR");
        }
    }

    private UUID fetchExistingAddressId(UUID userId) {
        List<UUID> ids = jdbc.query(
                "SELECT address_id FROM app_users WHERE id = :userId",
                Map.of("userId", userId),
                (rs, rowNum) -> rs.getObject("address_id", UUID.class));
        return ids.isEmpty() ? null : ids.get(0);
    }

    private DiscoveryPreferencesDto loadPreferences(UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(PREFERENCES_SELECT_SQL,
                Map.of("userId", userId));
        if (rows.isEmpty()) return null;
        return mapToPreferencesDto(rows.get(0));
    }

    private DiscoveryPreferencesDto mapToPreferencesDto(Map<String, Object> row) {
        List<LanguageOption> langPrefs = catalogService.expandLanguageIds(
                toUuidArray(row.get("language_preference_ids")));
        List<EthnicityOption> ethPrefs = catalogService.expandEthnicityIds(
                toUuidArray(row.get("ethnicity_preference_ids")));
        return new DiscoveryPreferencesDto(
                (String) row.get("interested_in_gender"),
                row.get("min_age") != null ? ((Number) row.get("min_age")).intValue() : null,
                row.get("max_age") != null ? ((Number) row.get("max_age")).intValue() : null,
                row.get("max_distance_km") != null ? ((Number) row.get("max_distance_km")).intValue() : null,
                (Boolean) row.get("show_verified_only"),
                (String) row.get("location_mode"),
                toStringList(row.get("specific_country_codes")),
                (Boolean) row.get("expand_search_when_limited"),
                (String) row.get("has_children_preference"),
                (String) row.get("wants_children_preference"),
                toStringList(row.get("religion_preferences")),
                langPrefs,
                ethPrefs,
                row.get("preferences_version") != null
                        ? ((Number) row.get("preferences_version")).intValue() : 1
        );
    }

    private ProfileAddressDto buildAddressDto(Map<String, Object> r) {
        Object addressId = r.get("address_id");
        if (addressId == null) return null;
        return new ProfileAddressDto(
                (UUID) addressId,
                (String) r.get("city"),
                (String) r.get("region"),
                (String) r.get("country_code"),
                (String) r.get("country_name"),
                (String) r.get("formatted_address"),
                (String) r.get("location_source")
        );
    }

    private String buildDisplayName(String city, String region, String countryName) {
        StringBuilder sb = new StringBuilder();
        if (city != null && !city.isBlank()) sb.append(city);
        if (region != null && !region.isBlank()) { if (!sb.isEmpty()) sb.append(", "); sb.append(region); }
        if (countryName != null && !countryName.isBlank()) { if (!sb.isEmpty()) sb.append(", "); sb.append(countryName); }
        return sb.toString();
    }

    private List<String> toStringList(Object obj) {
        if (obj == null) return List.of();
        if (obj instanceof String[] arr) return Arrays.asList(arr);
        if (obj instanceof java.sql.Array sqlArr) {
            try {
                Object[] arr = (Object[]) sqlArr.getArray();
                return arr == null ? List.of() : Arrays.stream(arr).map(Object::toString).toList();
            } catch (Exception ignored) {}
        }
        return List.of();
    }

    private String trimOrNull(String value) {
        return (value != null && !value.isBlank()) ? value.trim() : null;
    }

    private String trimOrFallback(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value.trim() : fallback;
    }

    private void ensureProfileExists(UUID userId, ProfileUpdateRequest request) {
        Boolean profileExists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM profiles WHERE user_id = :userId)",
                Map.of("userId", userId), Boolean.class);
        if (Boolean.TRUE.equals(profileExists)) {
            return;
        }

        String displayName = trimOrNull(request.displayName());
        if (displayName == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR");
        }
        String gender = request.gender();
        if (gender == null || !VALID_GENDERS.contains(gender)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR");
        }
        LocalDate dob = request.dateOfBirth();
        if (dob == null || dob.isAfter(LocalDate.now().minusYears(18))
                || dob.isBefore(LocalDate.now().minusYears(120))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR");
        }
        String relationshipIntention = request.relationshipIntention();
        if (relationshipIntention == null || !VALID_RELATIONSHIP_INTENTION.contains(relationshipIntention)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR");
        }

        validateOptionalEnum("marital_status", request.maritalStatus(), VALID_MARITAL_STATUS);
        validateOptionalEnum("education_level", request.educationLevel(), VALID_EDUCATION_LEVEL);
        validateOptionalEnum("religion", request.religion(), VALID_RELIGION);

        Boolean smoking = request.smoking();
        Boolean drinking = request.drinking();
        String smokingDetail = request.smokingDetail();
        String drinkingDetail = request.drinkingDetail();
        if (smokingDetail != null) {
            validateOptionalEnum("smoking_detail", smokingDetail, VALID_SMOKING_DETAIL);
            smoking = !"NO".equals(smokingDetail);
        }
        if (drinkingDetail != null) {
            validateOptionalEnum("drinking_detail", drinkingDetail, VALID_DRINKING_DETAIL);
            drinking = !"NO".equals(drinkingDetail);
        }

        String[] interestsArray = request.interests() != null
                ? request.interests().toArray(String[]::new) : new String[0];

        jdbc.update("""
                INSERT INTO profiles (
                    user_id, display_name, gender, date_of_birth, height_cm, residency_type,
                    bio, nationality, religion, education_level, occupation,
                    relationship_intention, marital_status, has_children, wants_children,
                    smoking, drinking, smoking_detail, drinking_detail,
                    activity_level, interests, profile_completion_score
                ) VALUES (
                    :userId, :displayName, :gender, :dateOfBirth, :heightCm, 'DIASPORA',
                    :bio, :nationality, :religion, :educationLevel, :occupation,
                    :relationshipIntention, :maritalStatus, :hasChildren, :wantsChildren,
                    :smoking, :drinking, :smokingDetail, :drinkingDetail,
                    :activityLevel, :interests, 0
                )
                """,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("displayName", displayName)
                        .addValue("gender", gender)
                        .addValue("dateOfBirth", dob)
                        .addValue("heightCm", request.heightCm())
                        .addValue("bio", trimOrNull(request.bio()))
                        .addValue("nationality", request.nationality())
                        .addValue("religion", request.religion())
                        .addValue("educationLevel", request.educationLevel())
                        .addValue("occupation", trimOrNull(request.occupation()))
                        .addValue("relationshipIntention", relationshipIntention)
                        .addValue("maritalStatus", request.maritalStatus())
                        .addValue("hasChildren", request.hasChildren())
                        .addValue("wantsChildren", request.wantsChildren())
                        .addValue("smoking", smoking)
                        .addValue("drinking", drinking)
                        .addValue("smokingDetail", smokingDetail)
                        .addValue("drinkingDetail", drinkingDetail)
                        .addValue("activityLevel", request.activityLevel())
                        .addValue("interests", interestsArray));
    }

    public OtherUserProfileDto getOtherUserProfile(UUID callerId, UUID targetUserId) {
        checkActorEligibility(callerId);

        boolean isBlocked = jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM user_blocks
                    WHERE status = 'ACTIVE'
                      AND ((blocker_user_id = :caller AND blocked_user_id = :target)
                           OR (blocker_user_id = :target AND blocked_user_id = :caller))
                )
                """,
                Map.of("caller", callerId, "target", targetUserId), Boolean.class);
        if (Boolean.TRUE.equals(isBlocked)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND");
        }

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT
                    p.user_id, p.display_name, p.gender,
                    calculate_age(p.date_of_birth) AS age,
                    p.bio, p.height_cm, p.residency_type,
                    p.ethnicity_ids, p.ethnicity_other_text,
                    p.nationality, p.religion, p.education_level, p.occupation,
                    p.relationship_intention, p.marital_status,
                    p.has_children, p.wants_children,
                    p.activity_level, p.interests, p.language_ids,
                    p.is_visible, p.is_verified,
                    a.id AS address_id, a.city, a.region, a.country_code, a.country_name,
                    a.formatted_address, a.location_source,
                    au.last_active_at, au.show_activity_status
                FROM profiles p
                JOIN app_users au ON au.id = p.user_id
                LEFT JOIN addresses a ON a.id = au.address_id
                WHERE p.user_id = :targetUserId
                """, Map.of("targetUserId", targetUserId));

        if (rows.isEmpty() || !Boolean.TRUE.equals(rows.get(0).get("is_visible"))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND");
        }

        Map<String, Object> r = rows.get(0);

        List<EthnicityOption> ethnicities = catalogService.expandEthnicityIds(
                toUuidArray(r.get("ethnicity_ids")));
        List<LanguageOption> languages = catalogService.expandLanguageIds(
                toUuidArray(r.get("language_ids")));

        List<Map<String, Object>> photoRows = jdbc.queryForList("""
                SELECT id, photo_order, is_primary, moderation_status, storage_bucket, storage_path
                FROM profile_photos
                WHERE user_id = :targetUserId AND deleted_at IS NULL AND moderation_status = 'APPROVED'
                ORDER BY photo_order ASC
                """, Map.of("targetUserId", targetUserId));

        List<ProfilePhotoDto> photos = photoRows.stream()
                .map(row -> profilePhotoService.buildPhotoDto(row, 3600))
                .toList();
        String primaryPhotoUrl = photos.stream()
                .filter(ph -> Boolean.TRUE.equals(ph.isPrimary()))
                .map(ProfilePhotoDto::signedUrl)
                .findFirst().orElse(null);

        RelationInfo relationInfo = resolveRelationInfo(callerId, targetUserId);

        java.time.OffsetDateTime lastActiveAt =
                r.get("last_active_at") instanceof java.time.OffsetDateTime odt ? odt : null;
        boolean showActivity = Boolean.TRUE.equals(r.get("show_activity_status"));
        ActivityStatus activityStatus = activityStatusService.resolve(showActivity, lastActiveAt, activityStatusService.now());

        return new OtherUserProfileDto(
                (UUID) r.get("user_id"),
                (String) r.get("display_name"),
                r.get("age") != null ? ((Number) r.get("age")).intValue() : null,
                (String) r.get("gender"),
                (String) r.get("bio"),
                r.get("height_cm") != null ? ((Number) r.get("height_cm")).intValue() : null,
                (String) r.get("residency_type"),
                buildAddressDto(r),
                ethnicities,
                (String) r.get("ethnicity_other_text"),
                (String) r.get("nationality"),
                (String) r.get("religion"),
                (String) r.get("education_level"),
                (String) r.get("occupation"),
                (String) r.get("relationship_intention"),
                (String) r.get("marital_status"),
                (Boolean) r.get("has_children"),
                (Boolean) r.get("wants_children"),
                (String) r.get("activity_level"),
                toStringList(r.get("interests")),
                languages,
                (Boolean) r.get("is_verified"),
                primaryPhotoUrl,
                photos,
                relationInfo.status(),
                relationInfo.matchId(),
                activityStatus
        );
    }

    @Transactional
    public VisibilityUpdateResponse updateVisibility(UUID userId, boolean isVisible) {
        checkActorEligibility(userId);

        if (isVisible) {
            boolean hasQualifiedPrimary = jdbc.queryForObject("""
                    SELECT EXISTS(
                        SELECT 1 FROM profile_photos
                        WHERE user_id = :userId AND deleted_at IS NULL
                          AND is_primary = TRUE
                          AND moderation_status IN ('APPROVED', 'MANUAL_REVIEW')
                    )
                    """, Map.of("userId", userId), Boolean.class);
            if (!Boolean.TRUE.equals(hasQualifiedPrimary)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "MISSING_APPROVED_PRIMARY_PHOTO");
            }
        }

        jdbc.update("UPDATE profiles SET is_visible = :isVisible WHERE user_id = :userId",
                Map.of("isVisible", isVisible, "userId", userId));

        int score = profilePhotoService.computeScore(userId);
        return new VisibilityUpdateResponse(isVisible, score);
    }

    private RelationInfo resolveRelationInfo(UUID callerId, UUID targetUserId) {
        List<Map<String, Object>> matchRows = jdbc.queryForList("""
                SELECT id FROM matches
                WHERE status = 'ACTIVE'
                  AND ((user_one_id = :caller AND user_two_id = :target)
                       OR (user_one_id = :target AND user_two_id = :caller))
                LIMIT 1
                """, Map.of("caller", callerId, "target", targetUserId));
        if (!matchRows.isEmpty()) {
            return new RelationInfo("MATCHED", (UUID) matchRows.get(0).get("id"));
        }

        boolean callerLiked = jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM user_discovery_actions
                    WHERE actor_user_id = :caller AND target_user_id = :target
                      AND action_type IN ('LIKE', 'SUPERLIKE') AND status = 'ACTIVE'
                )
                """, Map.of("caller", callerId, "target", targetUserId), Boolean.class);
        if (Boolean.TRUE.equals(callerLiked)) return new RelationInfo("LIKED", null);

        boolean targetLiked = jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM user_discovery_actions
                    WHERE actor_user_id = :target AND target_user_id = :caller
                      AND action_type IN ('LIKE', 'SUPERLIKE') AND status = 'ACTIVE'
                )
                """, Map.of("caller", callerId, "target", targetUserId), Boolean.class);
        if (Boolean.TRUE.equals(targetLiked)) return new RelationInfo("LIKED_YOU", null);

        return new RelationInfo("NONE", null);
    }

    @Transactional
    public ProfileMeDto updateCulturalDetails(UUID userId, CulturalDetailsRequest request) {
        checkActorEligibility(userId);

        List<UUID> ethIds = request.ethnicityIds() != null ? request.ethnicityIds() : Collections.emptyList();
        List<UUID> langIds = request.languageIds() != null ? request.languageIds() : Collections.emptyList();
        String otherText = request.ethnicityOtherText();

        if (otherText != null && otherText.isBlank()) otherText = null;
        if (otherText != null && otherText.length() > 200) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR");
        }

        catalogService.validateEthnicityIds(ethIds);
        catalogService.validateLanguageIds(langIds);

        String ethArray = CatalogService.buildUuidArrayParam(ethIds);
        String langArray = CatalogService.buildUuidArrayParam(langIds);

        jdbc.update("""
                UPDATE profiles SET
                    ethnicity_ids        = :ethIds::UUID[],
                    ethnicity_other_text = :otherText,
                    language_ids         = :langIds::UUID[]
                WHERE user_id = :userId
                """,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("ethIds", ethArray)
                        .addValue("otherText", otherText)
                        .addValue("langIds", langArray));

        int score = profilePhotoService.computeScore(userId);
        jdbc.update("UPDATE profiles SET profile_completion_score = :score WHERE user_id = :userId",
                Map.of("score", score, "userId", userId));

        return getCurrentProfile(userId);
    }

    private UUID[] toUuidArray(Object obj) {
        if (obj == null) return new UUID[0];
        if (obj instanceof java.sql.Array sqlArr) {
            try {
                Object[] raw = (Object[]) sqlArr.getArray();
                if (raw == null) return new UUID[0];
                UUID[] result = new UUID[raw.length];
                for (int i = 0; i < raw.length; i++) {
                    result[i] = raw[i] instanceof UUID u ? u : UUID.fromString(raw[i].toString());
                }
                return result;
            } catch (Exception ignored) {}
        }
        if (obj instanceof UUID[] arr) return arr;
        return new UUID[0];
    }

    private record RelationInfo(String status, UUID matchId) {}
}
