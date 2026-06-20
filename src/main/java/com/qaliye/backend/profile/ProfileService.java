package com.qaliye.backend.profile;

import com.qaliye.backend.onboarding.OnboardingService;
import com.qaliye.backend.user.entity.AppUser;
import com.qaliye.backend.user.entity.Profile;
import com.qaliye.backend.user.repository.AppUserRepository;
import com.qaliye.backend.user.repository.ProfileRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ProfileService {

    private static final Set<String> VALID_GENDERS = Set.of("MALE", "FEMALE", "OTHER");
    private static final Set<String> VALID_RESIDENCY = Set.of("ETHIOPIA", "ERITREA", "DIASPORA");
    private static final Set<String> VALID_INTENTIONS =
            Set.of("MARRIAGE", "SERIOUS_RELATIONSHIP", "LONG_TERM", "FRIENDSHIP", "NOT_SURE_YET");

    private final ProfileRepository profileRepository;
    private final AppUserRepository appUserRepository;
    private final OnboardingService onboardingService;
    private final NamedParameterJdbcTemplate jdbc;

    public ProfileService(ProfileRepository profileRepository,
                          AppUserRepository appUserRepository,
                          OnboardingService onboardingService,
                          NamedParameterJdbcTemplate jdbc) {
        this.profileRepository = profileRepository;
        this.appUserRepository = appUserRepository;
        this.onboardingService = onboardingService;
        this.jdbc = jdbc;
    }

    public Map<String, Object> getProfile(UUID userId) {
        Profile profile = profileRepository.findByUserId(userId).orElse(null);
        if (profile == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PROFILE_NOT_CREATED");
        }
        OnboardingService.OnboardingStatus status = onboardingService.getStatus(userId);
        return profileToMap(profile, status);
    }

    public Map<String, Object> getLocation(UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.location_source, a.city, a.region, a.country_code, a.country_name,
                       a.formatted_address, a.location_precision, a.location_place_id
                FROM addresses a
                JOIN app_users u ON u.address_id = a.id
                WHERE u.id = :userId
                """, Map.of("userId", userId));
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LOCATION_NOT_FOUND");
        }
        Map<String, Object> row = rows.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        String source = (String) row.get("location_source");
        result.put("location_source", source);
        result.put("city", row.get("city"));
        result.put("region", row.get("region"));
        result.put("country_code", row.get("country_code"));
        result.put("country_name", row.get("country_name"));
        result.put("location_precision", row.get("location_precision"));
        if ("MANUAL".equals(source)) {
            Object placeId = row.get("location_place_id");
            if (placeId != null) result.put("place_id", placeId.toString());
            result.put("display_name", row.get("formatted_address"));
        } else {
            result.put("formatted_address", row.get("formatted_address"));
            result.put("display_name", buildDisplayName(
                    (String) row.get("city"),
                    (String) row.get("region"),
                    (String) row.get("country_name")));
        }
        return result;
    }

    @Transactional
    public Map<String, Object> deleteLocation(UUID userId) {
        UUID existingAddressId = fetchExistingAddressId(userId);
        jdbc.update("UPDATE app_users SET address_id = NULL WHERE id = :userId", Map.of("userId", userId));
        if (existingAddressId != null) {
            jdbc.update("DELETE FROM addresses WHERE id = :addressId", Map.of("addressId", existingAddressId));
        }
        onboardingService.recomputeScore(userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("onboarding", onboardingStatusToMap(onboardingService.getStatus(userId)));
        return result;
    }

    @Transactional
    public Map<String, Object> updateProfile(UUID userId, UpdateProfileRequest request) {
        if (!VALID_GENDERS.contains(request.gender()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PROFILE");
        if (!VALID_RESIDENCY.contains(request.residencyType()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PROFILE");
        if (!VALID_INTENTIONS.contains(request.relationshipIntention()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PROFILE");

        LocalDate dob = request.dateOfBirth();
        if (dob.isAfter(LocalDate.now().minusYears(18)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNDERAGE");
        if (dob.isBefore(LocalDate.now().minusYears(120)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PROFILE");

        AppUser appUser = appUserRepository.getReferenceById(userId);
        Profile profile = profileRepository.findByUserId(userId).orElseGet(() -> {
            Profile p = new Profile();
            p.setAppUser(appUser); // @MapsId derives user_id from appUser.id
            p.setIsVisible(false);
            p.setIsOnboarded(false);
            p.setIsVerified(false);
            p.setProfileCompletionScore(0);
            return p;
        });

        profile.setDisplayName(request.displayName().trim());
        profile.setGender(request.gender());
        profile.setDateOfBirth(dob);
        profile.setResidencyType(request.residencyType());
        profile.setRelationshipIntention(request.relationshipIntention());
        profileRepository.save(profile);

        onboardingService.recomputeScore(userId);

        Profile updated = profileRepository.findByUserId(userId).orElse(profile);
        return profileToMap(updated, onboardingService.getStatus(userId));
    }

    @Transactional
    public Map<String, Object> setLocation(UUID userId, SetLocationRequest request) {
        String source = request.locationSource();
        if (source == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_LOCATION");

        Map<String, Object> locationData;
        if ("GPS".equalsIgnoreCase(source)) {
            locationData = setLocationByGps(userId, request);
        } else if ("MANUAL".equalsIgnoreCase(source)) {
            if (request.placeId() == null)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_LOCATION_PLACE");
            locationData = setLocationByPlace(userId, request.placeId());
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_LOCATION");
        }

        onboardingService.recomputeScore(userId);
        return locationData;
    }

    private Map<String, Object> setLocationByPlace(UUID userId, UUID placeId) {
        List<Map<String, Object>> places = jdbc.queryForList(
                "SELECT country_code, country_name, city, region, display_name, location_precision FROM location_places WHERE id = :placeId AND is_active = TRUE",
                Map.of("placeId", placeId));
        if (places.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "INVALID_LOCATION_PLACE");

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

        Map<String, Object> place = places.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("location_source", "MANUAL");
        result.put("city", place.get("city"));
        result.put("region", place.get("region"));
        result.put("country_name", place.get("country_name"));
        result.put("country_code", place.get("country_code"));
        result.put("display_name", place.get("display_name"));
        return result;
    }

    private Map<String, Object> setLocationByGps(UUID userId, SetLocationRequest req) {
        if (req.latitude() == null || req.longitude() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_LOCATION");
        double lat = req.latitude(), lng = req.longitude();
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_LOCATION");

        String city = trim(req.city(), "GPS Location");
        String countryCode = trim(req.countryCode(), "XX");
        String countryName = trim(req.countryName(), "Unknown");
        String region = req.region() != null ? req.region().trim() : null;
        String formatted = req.formattedAddress() != null ? req.formattedAddress().trim() : null;

        if (countryCode.length() > 2) countryCode = countryCode.substring(0, 2).toUpperCase();

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

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("location_source", "GPS");
        result.put("city", city);
        result.put("country_name", countryName);
        result.put("country_code", countryCode);
        return result;
    }

    private UUID fetchExistingAddressId(UUID userId) {
        List<UUID> ids = jdbc.query(
                "SELECT address_id FROM app_users WHERE id = :userId",
                Map.of("userId", userId),
                (rs, rowNum) -> rs.getObject("address_id", UUID.class));
        return ids.isEmpty() ? null : ids.get(0);
    }

    private Map<String, Object> onboardingStatusToMap(OnboardingService.OnboardingStatus s) {
        Map<String, Object> onboarding = new LinkedHashMap<>();
        onboarding.put("is_onboarded", s.isOnboarded());
        onboarding.put("next_step", s.nextStep());
        onboarding.put("can_complete_onboarding", s.canCompleteOnboarding());
        onboarding.put("can_enter_discovery", s.canEnterDiscovery());
        onboarding.put("blocking_reasons", s.blockingReasons());
        return onboarding;
    }

    private String buildDisplayName(String city, String region, String countryName) {
        StringBuilder sb = new StringBuilder();
        if (city != null && !city.isBlank()) sb.append(city);
        if (region != null && !region.isBlank()) { if (!sb.isEmpty()) sb.append(", "); sb.append(region); }
        if (countryName != null && !countryName.isBlank()) { if (!sb.isEmpty()) sb.append(", "); sb.append(countryName); }
        return sb.toString();
    }

    private Map<String, Object> profileToMap(Profile p, OnboardingService.OnboardingStatus status) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("display_name", p.getDisplayName());
        map.put("gender", p.getGender());
        map.put("date_of_birth", p.getDateOfBirth() != null ? p.getDateOfBirth().toString() : null);
        map.put("residency_type", p.getResidencyType());
        map.put("relationship_intention", p.getRelationshipIntention());
        map.put("is_onboarded", p.getIsOnboarded());
        map.put("profile_completion_score", status.profileCompletionScore());

        Map<String, Object> onboarding = new LinkedHashMap<>();
        onboarding.put("next_step", status.nextStep());
        onboarding.put("can_complete_onboarding", status.canCompleteOnboarding());
        map.put("onboarding", onboarding);
        return map;
    }

    private String trim(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value.trim() : fallback;
    }
}
