package com.qaliye.backend.profile;

import com.qaliye.backend.activity.ActivityStatusService;
import com.qaliye.backend.billing.repository.ActionLimitRepository;
import com.qaliye.backend.billing.service.ActionCostService;
import com.qaliye.backend.billing.service.CreditService;
import com.qaliye.backend.catalog.CatalogService;
import com.qaliye.backend.profile.dto.OtherUserProfileDto;
import com.qaliye.backend.profile.dto.ProfileMeDto;
import com.qaliye.backend.profile.dto.ProfilePhotosResponse;
import com.qaliye.backend.profile.dto.ProfileUpdateRequest;
import com.qaliye.backend.user.AdminUserService;
import com.qaliye.backend.user.UserStatusService;
import com.qaliye.backend.user.dto.AdminUserDetailDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProfileMarriagePreferencesTest {

    // -----------------------------------------------------------------------
    // DTO @Pattern validation — no Spring context required
    // -----------------------------------------------------------------------

    static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    static ProfileUpdateRequest requestWith(String marriageTimeline, String longDistance,
                                            String familyInvolvement, String religionImportance,
                                            String willingToRelocate) {
        return new ProfileUpdateRequest(
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                marriageTimeline, longDistance, familyInvolvement, religionImportance, willingToRelocate);
    }

    @ParameterizedTest
    @ValueSource(strings = {"WITHIN_6_MONTHS", "WITHIN_1_YEAR", "1_TO_2_YEARS", "2_TO_5_YEARS", "MORE_THAN_5_YEARS", "NOT_SURE"})
    void marriageTimeline_validValues_passValidation(String value) {
        Set<ConstraintViolation<ProfileUpdateRequest>> violations = VALIDATOR.validate(requestWith(value, null, null, null, null));
        assertThat(violations).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ASAP", "SOON", "NEVER", "TRUE", "UNKNOWN_VALUE"})
    void marriageTimeline_invalidValues_failValidation(String value) {
        Set<ConstraintViolation<ProfileUpdateRequest>> violations = VALIDATOR.validate(requestWith(value, null, null, null, null));
        assertThat(violations).isNotEmpty();
    }

    @Test
    void marriageTimeline_null_passesValidation() {
        assertThat(VALIDATOR.validate(requestWith(null, null, null, null, null))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"YES", "NO", "MAYBE"})
    void longDistanceRelationship_validValues_passValidation(String value) {
        assertThat(VALIDATOR.validate(requestWith(null, value, null, null, null))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SOMETIMES", "TRUE", "FALSE", "OPEN"})
    void longDistanceRelationship_invalidValues_failValidation(String value) {
        assertThat(VALIDATOR.validate(requestWith(null, value, null, null, null))).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"VERY_IMPORTANT", "IMPORTANT", "SOMEWHAT_IMPORTANT", "NOT_IMPORTANT"})
    void familyInvolvement_validValues_passValidation(String value) {
        assertThat(VALIDATOR.validate(requestWith(null, null, value, null, null))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"VERY", "CRITICAL", "TRUE", "FALSE"})
    void familyInvolvement_invalidValues_failValidation(String value) {
        assertThat(VALIDATOR.validate(requestWith(null, null, value, null, null))).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"VERY_IMPORTANT", "SOMEWHAT_IMPORTANT", "NOT_IMPORTANT"})
    void religionImportance_validValues_passValidation(String value) {
        assertThat(VALIDATOR.validate(requestWith(null, null, null, value, null))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"IMPORTANT", "CRITICAL", "NONE", "TRUE"})
    void religionImportance_invalidValues_failValidation(String value) {
        assertThat(VALIDATOR.validate(requestWith(null, null, null, value, null))).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"YES", "NO", "MAYBE"})
    void willingToRelocate_validValues_passValidation(String value) {
        assertThat(VALIDATOR.validate(requestWith(null, null, null, null, value))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPEN", "NEVER", "SOMETIMES", "TRUE"})
    void willingToRelocate_invalidValues_failValidation(String value) {
        assertThat(VALIDATOR.validate(requestWith(null, null, null, null, value))).isNotEmpty();
    }

    // -----------------------------------------------------------------------
    // ProfileService.updateProfile — 5 fields written to MapSqlParameterSource
    // -----------------------------------------------------------------------

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    class UpdateProfilePersistenceTests {

        @Mock NamedParameterJdbcTemplate jdbc;
        @Mock ProfilePhotoService profilePhotoService;
        @Mock ActivityStatusService activityStatusService;
        @Mock CatalogService catalogService;
        @Mock ActionCostService actionCostService;
        @Mock CreditService creditService;
        @Mock ActionLimitRepository actionLimitRepo;

        ProfileService service;

        @BeforeEach
        void setUp() {
            service = new ProfileService(jdbc, profilePhotoService, activityStatusService,
                    catalogService, actionCostService, creditService, actionLimitRepo);
            when(jdbc.queryForList(contains("deleted_at FROM app_users"), anyMap()))
                    .thenReturn(List.of(Map.of("status", "ACTIVE")));
            when(jdbc.queryForObject(
                    eq("SELECT EXISTS(SELECT 1 FROM profiles WHERE user_id = :userId)"),
                    anyMap(), eq(Boolean.class))).thenReturn(Boolean.TRUE);
            when(profilePhotoService.computeScore(any())).thenReturn(30);
            when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
            when(jdbc.queryForList(contains("p.user_id, p.display_name"), anyMap()))
                    .thenReturn(List.of(minimalProfileRow(UUID.randomUUID())));
            when(profilePhotoService.getPhotos(any()))
                    .thenReturn(new ProfilePhotosResponse(List.of()));
        }

        @Test
        void updateProfile_persistsAllFiveMarriageFields() {
            UUID userId = UUID.randomUUID();
            ProfileUpdateRequest request = new ProfileUpdateRequest(
                    null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null,
                    "WITHIN_1_YEAR", "MAYBE", "IMPORTANT", "VERY_IMPORTANT", "YES");

            ArgumentCaptor<MapSqlParameterSource> captor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
            service.updateProfile(userId, request);

            verify(jdbc, atLeastOnce()).update(contains("marriage_timeline"), captor.capture());
            MapSqlParameterSource params = captor.getValue();
            assertThat(params.getValue("marriageTimeline")).isEqualTo("WITHIN_1_YEAR");
            assertThat(params.getValue("longDistanceRelationship")).isEqualTo("MAYBE");
            assertThat(params.getValue("familyInvolvement")).isEqualTo("IMPORTANT");
            assertThat(params.getValue("religionImportance")).isEqualTo("VERY_IMPORTANT");
            assertThat(params.getValue("willingToRelocate")).isEqualTo("YES");
        }

        @Test
        void updateProfile_nullMarriageFields_persistsNulls() {
            UUID userId = UUID.randomUUID();
            ProfileUpdateRequest request = new ProfileUpdateRequest(
                    null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null,
                    null, null, null, null, null);

            ArgumentCaptor<MapSqlParameterSource> captor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
            service.updateProfile(userId, request);

            verify(jdbc, atLeastOnce()).update(contains("marriage_timeline"), captor.capture());
            MapSqlParameterSource params = captor.getValue();
            assertThat(params.getValue("marriageTimeline")).isNull();
            assertThat(params.getValue("longDistanceRelationship")).isNull();
            assertThat(params.getValue("familyInvolvement")).isNull();
            assertThat(params.getValue("religionImportance")).isNull();
            assertThat(params.getValue("willingToRelocate")).isNull();
        }

        @Test
        void updateProfile_allMarriageTimelineValues_persistedWithoutError() {
            UUID userId = UUID.randomUUID();
            for (String value : List.of("WITHIN_6_MONTHS", "WITHIN_1_YEAR", "1_TO_2_YEARS",
                    "2_TO_5_YEARS", "MORE_THAN_5_YEARS", "NOT_SURE")) {
                ProfileUpdateRequest request = new ProfileUpdateRequest(
                        null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null,
                        value, null, null, null, null);
                ArgumentCaptor<MapSqlParameterSource> captor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
                service.updateProfile(userId, request);
                verify(jdbc, atLeastOnce()).update(contains("marriage_timeline"), captor.capture());
                assertThat(captor.getValue().getValue("marriageTimeline")).isEqualTo(value);
                clearInvocations(jdbc);
            }
        }
    }

    // -----------------------------------------------------------------------
    // ProfileService.ensureProfileExists — 5 fields in INSERT
    // -----------------------------------------------------------------------

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    class CreateProfilePersistenceTests {

        @Mock NamedParameterJdbcTemplate jdbc;
        @Mock ProfilePhotoService profilePhotoService;
        @Mock ActivityStatusService activityStatusService;
        @Mock CatalogService catalogService;
        @Mock ActionCostService actionCostService;
        @Mock CreditService creditService;
        @Mock ActionLimitRepository actionLimitRepo;

        ProfileService service;

        @BeforeEach
        void setUp() {
            service = new ProfileService(jdbc, profilePhotoService, activityStatusService,
                    catalogService, actionCostService, creditService, actionLimitRepo);
            when(jdbc.queryForList(contains("deleted_at FROM app_users"), anyMap()))
                    .thenReturn(List.of(Map.of("status", "ACTIVE")));
            when(jdbc.queryForObject(
                    eq("SELECT EXISTS(SELECT 1 FROM profiles WHERE user_id = :userId)"),
                    anyMap(), eq(Boolean.class))).thenReturn(Boolean.FALSE);
            when(profilePhotoService.computeScore(any())).thenReturn(0);
            when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
            when(jdbc.queryForList(contains("p.user_id, p.display_name"), anyMap()))
                    .thenReturn(List.of(minimalProfileRow(UUID.randomUUID())));
            when(profilePhotoService.getPhotos(any()))
                    .thenReturn(new ProfilePhotosResponse(List.of()));
        }

        @Test
        void createProfile_persistsAllFiveMarriageFields() {
            UUID userId = UUID.randomUUID();
            ProfileUpdateRequest request = new ProfileUpdateRequest(
                    "Jane Doe", "FEMALE", java.time.LocalDate.of(1995, 5, 20),
                    null, null, null, null, null, null,
                    "MARRIAGE", null, null, null,
                    null, null, null, null, null, null, null, null, null, null,
                    "2_TO_5_YEARS", "YES", "VERY_IMPORTANT", "SOMEWHAT_IMPORTANT", "NO");

            ArgumentCaptor<MapSqlParameterSource> captor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
            service.updateProfile(userId, request);

            verify(jdbc, atLeastOnce()).update(contains("INSERT INTO profiles"), captor.capture());
            MapSqlParameterSource params = captor.getValue();
            assertThat(params.getValue("marriageTimeline")).isEqualTo("2_TO_5_YEARS");
            assertThat(params.getValue("longDistanceRelationship")).isEqualTo("YES");
            assertThat(params.getValue("familyInvolvement")).isEqualTo("VERY_IMPORTANT");
            assertThat(params.getValue("religionImportance")).isEqualTo("SOMEWHAT_IMPORTANT");
            assertThat(params.getValue("willingToRelocate")).isEqualTo("NO");
        }

        @Test
        void createProfile_nullMarriageFields_insertedAsNull() {
            UUID userId = UUID.randomUUID();
            ProfileUpdateRequest request = new ProfileUpdateRequest(
                    "John Doe", "MALE", java.time.LocalDate.of(1990, 1, 1),
                    null, null, null, null, null, null,
                    "MARRIAGE", null, null, null,
                    null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null);

            ArgumentCaptor<MapSqlParameterSource> captor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
            service.updateProfile(userId, request);

            verify(jdbc, atLeastOnce()).update(contains("INSERT INTO profiles"), captor.capture());
            MapSqlParameterSource params = captor.getValue();
            assertThat(params.getValue("marriageTimeline")).isNull();
            assertThat(params.getValue("longDistanceRelationship")).isNull();
            assertThat(params.getValue("familyInvolvement")).isNull();
            assertThat(params.getValue("religionImportance")).isNull();
            assertThat(params.getValue("willingToRelocate")).isNull();
        }
    }

    // -----------------------------------------------------------------------
    // ProfileService.getCurrentProfile — 5 fields mapped to ProfileMeDto
    // -----------------------------------------------------------------------

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    class GetCurrentProfileMappingTests {

        @Mock NamedParameterJdbcTemplate jdbc;
        @Mock ProfilePhotoService profilePhotoService;
        @Mock ActivityStatusService activityStatusService;
        @Mock CatalogService catalogService;
        @Mock ActionCostService actionCostService;
        @Mock CreditService creditService;
        @Mock ActionLimitRepository actionLimitRepo;

        ProfileService service;

        @BeforeEach
        void setUp() {
            service = new ProfileService(jdbc, profilePhotoService, activityStatusService,
                    catalogService, actionCostService, creditService, actionLimitRepo);
            when(jdbc.queryForList(contains("deleted_at FROM app_users"), anyMap()))
                    .thenReturn(List.of(Map.of("status", "ACTIVE")));
            when(profilePhotoService.getPhotos(any()))
                    .thenReturn(new ProfilePhotosResponse(List.of()));
            when(catalogService.expandEthnicityIds(any())).thenReturn(List.of());
            when(catalogService.expandLanguageIds(any())).thenReturn(List.of());
            when(jdbc.queryForList(contains("interested_in_gender"), anyMap())).thenReturn(List.of());
        }

        @Test
        void getCurrentProfile_returns5MarriageFields() {
            UUID userId = UUID.randomUUID();
            Map<String, Object> row = minimalProfileRow(userId);
            row.put("marriage_timeline", "1_TO_2_YEARS");
            row.put("long_distance_relationship", "NO");
            row.put("family_involvement", "VERY_IMPORTANT");
            row.put("religion_importance", "SOMEWHAT_IMPORTANT");
            row.put("willing_to_relocate", "MAYBE");
            when(jdbc.queryForList(contains("p.user_id, p.display_name"), anyMap()))
                    .thenReturn(List.of(row));

            ProfileMeDto dto = service.getCurrentProfile(userId);

            assertThat(dto.marriageTimeline()).isEqualTo("1_TO_2_YEARS");
            assertThat(dto.longDistanceRelationship()).isEqualTo("NO");
            assertThat(dto.familyInvolvement()).isEqualTo("VERY_IMPORTANT");
            assertThat(dto.religionImportance()).isEqualTo("SOMEWHAT_IMPORTANT");
            assertThat(dto.willingToRelocate()).isEqualTo("MAYBE");
        }

        @Test
        void getCurrentProfile_nullMarriageFields_returnedAsNull() {
            UUID userId = UUID.randomUUID();
            when(jdbc.queryForList(contains("p.user_id, p.display_name"), anyMap()))
                    .thenReturn(List.of(minimalProfileRow(userId)));

            ProfileMeDto dto = service.getCurrentProfile(userId);

            assertThat(dto.marriageTimeline()).isNull();
            assertThat(dto.longDistanceRelationship()).isNull();
            assertThat(dto.familyInvolvement()).isNull();
            assertThat(dto.religionImportance()).isNull();
            assertThat(dto.willingToRelocate()).isNull();
        }

        @Test
        void getCurrentProfile_allLongDistanceValues_mappedCorrectly() {
            for (String value : List.of("YES", "NO", "MAYBE")) {
                UUID userId = UUID.randomUUID();
                Map<String, Object> row = minimalProfileRow(userId);
                row.put("long_distance_relationship", value);
                when(jdbc.queryForList(contains("p.user_id, p.display_name"), anyMap()))
                        .thenReturn(List.of(row));
                ProfileMeDto dto = service.getCurrentProfile(userId);
                assertThat(dto.longDistanceRelationship()).isEqualTo(value);
            }
        }
    }

    // -----------------------------------------------------------------------
    // ProfileService.getOtherUserProfile — 5 fields mapped to OtherUserProfileDto
    // -----------------------------------------------------------------------

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    class GetOtherUserProfileMappingTests {

        @Mock NamedParameterJdbcTemplate jdbc;
        @Mock ProfilePhotoService profilePhotoService;
        @Mock ActivityStatusService activityStatusService;
        @Mock CatalogService catalogService;
        @Mock ActionCostService actionCostService;
        @Mock CreditService creditService;
        @Mock ActionLimitRepository actionLimitRepo;

        ProfileService service;
        UUID callerId;
        UUID targetId;

        @BeforeEach
        void setUp() {
            service = new ProfileService(jdbc, profilePhotoService, activityStatusService,
                    catalogService, actionCostService, creditService, actionLimitRepo);
            callerId = UUID.randomUUID();
            targetId = UUID.randomUUID();
            when(jdbc.queryForList(contains("deleted_at FROM app_users"), anyMap()))
                    .thenReturn(List.of(Map.of("status", "ACTIVE")));
            when(jdbc.queryForObject(contains("user_blocks"), anyMap(), eq(Boolean.class)))
                    .thenReturn(Boolean.FALSE);
            when(catalogService.expandEthnicityIds(any())).thenReturn(List.of());
            when(catalogService.expandLanguageIds(any())).thenReturn(List.of());
            when(jdbc.queryForList(contains("profile_photos"), anyMap())).thenReturn(List.of());
            when(jdbc.queryForList(contains("SELECT id FROM matches"), anyMap())).thenReturn(List.of());
            when(jdbc.queryForObject(contains("user_discovery_actions"), anyMap(), eq(Boolean.class)))
                    .thenReturn(Boolean.FALSE);
        }

        @Test
        void getOtherUserProfile_returns5MarriageFields() {
            Map<String, Object> row = minimalOtherProfileRow(targetId);
            row.put("marriage_timeline", "MORE_THAN_5_YEARS");
            row.put("long_distance_relationship", "YES");
            row.put("family_involvement", "NOT_IMPORTANT");
            row.put("religion_importance", "NOT_IMPORTANT");
            row.put("willing_to_relocate", "NO");
            when(jdbc.queryForList(contains("p.user_id, p.display_name"), anyMap()))
                    .thenReturn(List.of(row));

            OtherUserProfileDto dto = service.getOtherUserProfile(callerId, targetId);

            assertThat(dto.marriageTimeline()).isEqualTo("MORE_THAN_5_YEARS");
            assertThat(dto.longDistanceRelationship()).isEqualTo("YES");
            assertThat(dto.familyInvolvement()).isEqualTo("NOT_IMPORTANT");
            assertThat(dto.religionImportance()).isEqualTo("NOT_IMPORTANT");
            assertThat(dto.willingToRelocate()).isEqualTo("NO");
        }

        @Test
        void getOtherUserProfile_nullMarriageFields_returnedAsNull() {
            when(jdbc.queryForList(contains("p.user_id, p.display_name"), anyMap()))
                    .thenReturn(List.of(minimalOtherProfileRow(targetId)));

            OtherUserProfileDto dto = service.getOtherUserProfile(callerId, targetId);

            assertThat(dto.marriageTimeline()).isNull();
            assertThat(dto.longDistanceRelationship()).isNull();
            assertThat(dto.familyInvolvement()).isNull();
            assertThat(dto.religionImportance()).isNull();
            assertThat(dto.willingToRelocate()).isNull();
        }
    }

    // -----------------------------------------------------------------------
    // AdminUserService.getUserDetail — 5 fields mapped to AdminUserDetailDto
    // -----------------------------------------------------------------------

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    class AdminUserDetailMappingTests {

        @Mock NamedParameterJdbcTemplate jdbc;
        @Mock UserStatusService userStatusService;
        @Mock CacheManager cacheManager;

        AdminUserService adminService;

        @BeforeEach
        void setUp() {
            adminService = new AdminUserService(jdbc, userStatusService, cacheManager);
        }

        @Test
        void getUserDetail_returns5MarriageFields() {
            UUID userId = UUID.randomUUID();
            Map<String, Object> row = adminDetailRow(userId);
            row.put("marriage_timeline", "WITHIN_6_MONTHS");
            row.put("long_distance_relationship", "MAYBE");
            row.put("family_involvement", "SOMEWHAT_IMPORTANT");
            row.put("religion_importance", "VERY_IMPORTANT");
            row.put("willing_to_relocate", "YES");
            stubAdminQueries(userId, row);

            AdminUserDetailDto dto = adminService.getUserDetail(userId);

            assertThat(dto.marriageTimeline()).isEqualTo("WITHIN_6_MONTHS");
            assertThat(dto.longDistanceRelationship()).isEqualTo("MAYBE");
            assertThat(dto.familyInvolvement()).isEqualTo("SOMEWHAT_IMPORTANT");
            assertThat(dto.religionImportance()).isEqualTo("VERY_IMPORTANT");
            assertThat(dto.willingToRelocate()).isEqualTo("YES");
        }

        @Test
        void getUserDetail_nullMarriageFields_returnedAsNull() {
            UUID userId = UUID.randomUUID();
            stubAdminQueries(userId, adminDetailRow(userId));

            AdminUserDetailDto dto = adminService.getUserDetail(userId);

            assertThat(dto.marriageTimeline()).isNull();
            assertThat(dto.longDistanceRelationship()).isNull();
            assertThat(dto.familyInvolvement()).isNull();
            assertThat(dto.religionImportance()).isNull();
            assertThat(dto.willingToRelocate()).isNull();
        }

        @SuppressWarnings("unchecked")
        private void stubAdminQueries(UUID userId, Map<String, Object> row) {
            when(jdbc.queryForList(contains("p.display_name"), anyMap())).thenReturn(List.of(row));
            Map<String, Object> photoStats = Map.of(
                    "total", 0L, "pending", 0L, "approved", 0L, "rejected", 0L, "manual_review", 0L);
            Map<String, Object> reportStats = Map.of("total", 0L, "pending", 0L);
            when(jdbc.queryForList(contains("profile_photos"), anyMap())).thenReturn(List.of(photoStats));
            when(jdbc.queryForList(contains("user_reports"), anyMap())).thenReturn(List.of(reportStats));
            when(jdbc.query(contains("user_verifications"), anyMap(), any(RowMapper.class)))
                    .thenReturn(List.of());
            when(jdbc.queryForObject(contains("matches"), anyMap(), eq(Long.class))).thenReturn(0L);
        }

        private Map<String, Object> adminDetailRow(UUID userId) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", userId);
            row.put("display_name", "Admin Test User");
            row.put("status", "ACTIVE");
            row.put("role", "USER");
            row.put("preferred_language", "en");
            row.put("gender", "MALE");
            row.put("residency_type", "DIASPORA");
            row.put("relationship_intention", "MARRIAGE");
            row.put("is_onboarded", true);
            row.put("is_verified", false);
            row.put("is_visible", true);
            row.put("profile_completion_score", 60);
            row.put("last_active_at", null);
            row.put("deleted_at", null);
            row.put("created_at", Timestamp.from(OffsetDateTime.now(ZoneOffset.UTC).toInstant()));
            row.put("updated_at", Timestamp.from(OffsetDateTime.now(ZoneOffset.UTC).toInstant()));
            return row;
        }
    }

    // -----------------------------------------------------------------------
    // Shared row-building helpers
    // -----------------------------------------------------------------------

    static Map<String, Object> minimalProfileRow(UUID userId) {
        Map<String, Object> row = new HashMap<>();
        row.put("user_id", userId);
        row.put("display_name", "Test User");
        row.put("gender", "MALE");
        row.put("date_of_birth", java.sql.Date.valueOf("1990-01-01"));
        row.put("age", 34);
        row.put("bio", null);
        row.put("height_cm", null);
        row.put("residency_type", "DIASPORA");
        row.put("ethnicity_ids", null);
        row.put("ethnicity_other_text", null);
        row.put("nationality", null);
        row.put("religion", null);
        row.put("education_level", null);
        row.put("occupation", null);
        row.put("relationship_intention", "MARRIAGE");
        row.put("marital_status", null);
        row.put("has_children", null);
        row.put("wants_children", null);
        row.put("smoking", null);
        row.put("drinking", null);
        row.put("smoking_detail", null);
        row.put("drinking_detail", null);
        row.put("activity_level", null);
        row.put("interests", null);
        row.put("language_ids", null);
        row.put("discovery_mode", "PUBLIC");
        row.put("is_visible", false);
        row.put("is_onboarded", false);
        row.put("is_verified", false);
        row.put("profile_completion_score", 0);
        row.put("role", "USER");
        row.put("verification_status", null);
        row.put("address_id", null);
        return row;
    }

    static Map<String, Object> minimalOtherProfileRow(UUID userId) {
        Map<String, Object> row = new HashMap<>();
        row.put("user_id", userId);
        row.put("display_name", "Other User");
        row.put("gender", "FEMALE");
        row.put("age", 28);
        row.put("bio", null);
        row.put("height_cm", null);
        row.put("residency_type", "ETHIOPIA");
        row.put("ethnicity_ids", null);
        row.put("ethnicity_other_text", null);
        row.put("nationality", null);
        row.put("religion", null);
        row.put("education_level", null);
        row.put("occupation", null);
        row.put("relationship_intention", "MARRIAGE");
        row.put("marital_status", null);
        row.put("has_children", null);
        row.put("wants_children", null);
        row.put("activity_level", null);
        row.put("interests", null);
        row.put("language_ids", null);
        row.put("is_visible", true);
        row.put("is_verified", false);
        row.put("address_id", null);
        row.put("last_active_at", null);
        row.put("show_activity_status", false);
        return row;
    }
}
