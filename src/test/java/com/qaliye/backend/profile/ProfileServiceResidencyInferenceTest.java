package com.qaliye.backend.profile;

import com.qaliye.backend.activity.ActivityStatusService;
import com.qaliye.backend.billing.repository.ActionLimitRepository;
import com.qaliye.backend.billing.service.ActionCostService;
import com.qaliye.backend.billing.service.CreditService;
import com.qaliye.backend.catalog.CatalogService;
import com.qaliye.backend.profile.dto.ProfileUpdateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProfileServiceResidencyInferenceTest {

    // -----------------------------------------------------------------------
    // inferResidencyType — pure static function tests (no mocks needed)
    // -----------------------------------------------------------------------

    @Test
    void inferResidencyType_ethiopia_uppercase() {
        assertThat(ProfileService.inferResidencyType("ET")).isEqualTo("ETHIOPIA");
    }

    @Test
    void inferResidencyType_ethiopia_lowercase() {
        assertThat(ProfileService.inferResidencyType("et")).isEqualTo("ETHIOPIA");
    }

    @Test
    void inferResidencyType_ethiopia_mixedCase() {
        assertThat(ProfileService.inferResidencyType("Et")).isEqualTo("ETHIOPIA");
    }

    @Test
    void inferResidencyType_eritrea_uppercase() {
        assertThat(ProfileService.inferResidencyType("ER")).isEqualTo("ERITREA");
    }

    @Test
    void inferResidencyType_eritrea_lowercase() {
        assertThat(ProfileService.inferResidencyType("er")).isEqualTo("ERITREA");
    }

    @Test
    void inferResidencyType_anotherCountry_diaspora() {
        assertThat(ProfileService.inferResidencyType("US")).isEqualTo("DIASPORA");
    }

    @Test
    void inferResidencyType_ukCode_diaspora() {
        assertThat(ProfileService.inferResidencyType("GB")).isEqualTo("DIASPORA");
    }

    @Test
    void inferResidencyType_unknownCode_diaspora() {
        assertThat(ProfileService.inferResidencyType("XX")).isEqualTo("DIASPORA");
    }

    @ParameterizedTest
    @NullAndEmptySource
    void inferResidencyType_nullOrEmpty_diaspora(String code) {
        assertThat(ProfileService.inferResidencyType(code)).isEqualTo("DIASPORA");
    }

    @Test
    void inferResidencyType_blankWithWhitespace_diaspora() {
        assertThat(ProfileService.inferResidencyType("   ")).isEqualTo("DIASPORA");
    }

    @Test
    void inferResidencyType_leadingTrailingSpaces_normalized() {
        assertThat(ProfileService.inferResidencyType(" ET ")).isEqualTo("ETHIOPIA");
    }

    // -----------------------------------------------------------------------
    // GPS setLocation — validation + residency persistence
    // -----------------------------------------------------------------------

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    class GpsLocationTests {

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
            service = new ProfileService(jdbc, profilePhotoService, activityStatusService, catalogService, actionCostService, creditService, actionLimitRepo);
        }

        @Test
        void nullCountryCode_throws422() {
            SetLocationRequest req = new SetLocationRequest(
                    "GPS", 9.0, 38.0, "Addis Ababa", null, "Ethiopia", null, null, null);
            assertThatThrownBy(() -> service.setLocation(UUID.randomUUID(), req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(422));
        }

        @Test
        void blankCountryCode_throws422() {
            SetLocationRequest req = new SetLocationRequest(
                    "GPS", 9.0, 38.0, "Addis Ababa", "  ", "Ethiopia", null, null, null);
            assertThatThrownBy(() -> service.setLocation(UUID.randomUUID(), req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(422));
        }

        @Test
        void numericCountryCode_throws422() {
            SetLocationRequest req = new SetLocationRequest(
                    "GPS", 9.0, 38.0, "City", "12", "Country", null, null, null);
            assertThatThrownBy(() -> service.setLocation(UUID.randomUUID(), req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(422));
        }

        @Test
        void ethiopiaCode_persistsEthiopia() {
            UUID userId = UUID.randomUUID();
            stubFlow(userId);
            service.setLocation(userId, gpsReq(9.0, 38.0, "Addis", "ET", "Ethiopia"));
            verifyResidency(userId, "ETHIOPIA");
        }

        @Test
        void eritreaCode_persistsEritrea() {
            UUID userId = UUID.randomUUID();
            stubFlow(userId);
            service.setLocation(userId, gpsReq(15.0, 39.0, "Asmara", "ER", "Eritrea"));
            verifyResidency(userId, "ERITREA");
        }

        @Test
        void otherCountryCode_persistsDiaspora() {
            UUID userId = UUID.randomUUID();
            stubFlow(userId);
            service.setLocation(userId, gpsReq(51.5, -0.1, "London", "GB", "United Kingdom"));
            verifyResidency(userId, "DIASPORA");
        }

        @Test
        void lowercaseCode_normalizedAndPersisted() {
            UUID userId = UUID.randomUUID();
            stubFlow(userId);
            service.setLocation(userId, gpsReq(9.0, 38.0, "Addis", "et", "Ethiopia"));
            verifyResidency(userId, "ETHIOPIA");
        }

        private SetLocationRequest gpsReq(double lat, double lng, String city, String cc, String countryName) {
            return new SetLocationRequest("GPS", lat, lng, city, cc, countryName, "SomeRegion", city + ", " + countryName, null);
        }

        @SuppressWarnings("unchecked")
        private void stubFlow(UUID userId) {
            UUID addressId = UUID.randomUUID();
            when(jdbc.query(contains("address_id FROM app_users"), anyMap(),
                    any(org.springframework.jdbc.core.RowMapper.class)))
                    .thenReturn(List.of(addressId));
            when(jdbc.update(anyString(), anyMap())).thenReturn(1);
            when(profilePhotoService.computeScore(userId)).thenReturn(30);
            Map<String, Object> row = new HashMap<>();
            row.put("id", UUID.randomUUID());
            row.put("location_source", "GPS");
            row.put("city", "City");
            row.put("region", null);
            row.put("country_code", "ET");
            row.put("country_name", "Country");
            row.put("formatted_address", "City, Country");
            row.put("location_precision", "EXACT");
            row.put("location_place_id", null);
            when(jdbc.queryForList(contains("location_source"), anyMap()))
                    .thenReturn(List.of(row));
        }

        private void verifyResidency(UUID userId, String expected) {
            verify(jdbc).update(
                    eq("UPDATE profiles SET residency_type = :rt WHERE user_id = :userId"),
                    eq(Map.of("rt", expected, "userId", userId)));
        }
    }

    // -----------------------------------------------------------------------
    // updateProfile — must NOT write residency_type from client request
    // -----------------------------------------------------------------------

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    class UpdateProfileResidencyTests {

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
            service = new ProfileService(jdbc, profilePhotoService, activityStatusService, catalogService, actionCostService, creditService, actionLimitRepo);
        }

        @Test
        void updateProfile_doesNotWriteResidencyType() {
            UUID userId = UUID.randomUUID();

            when(jdbc.queryForList(contains("deleted_at FROM app_users"), anyMap()))
                    .thenReturn(List.of(Map.of("status", "ACTIVE")));
            when(jdbc.queryForObject(
                    eq("SELECT EXISTS(SELECT 1 FROM profiles WHERE user_id = :userId)"),
                    anyMap(), eq(Boolean.class))).thenReturn(Boolean.TRUE);
            when(profilePhotoService.computeScore(userId)).thenReturn(40);
            when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

            Map<String, Object> profileRow = new HashMap<>();
            profileRow.put("user_id", userId);
            profileRow.put("display_name", "Test Name");
            profileRow.put("gender", "MALE");
            profileRow.put("residency_type", "DIASPORA");
            profileRow.put("relationship_intention", "MARRIAGE");
            profileRow.put("is_visible", false);
            profileRow.put("is_onboarded", false);
            profileRow.put("is_verified", false);
            profileRow.put("profile_completion_score", 40);
            profileRow.put("role", "USER");
            when(jdbc.queryForList(contains("p.user_id, p.display_name"), anyMap()))
                    .thenReturn(List.of(profileRow));
            when(profilePhotoService.getPhotos(userId))
                    .thenReturn(new com.qaliye.backend.profile.dto.ProfilePhotosResponse(List.of()));

            ProfileUpdateRequest request = new ProfileUpdateRequest(
                    "Test Name", "MALE", java.time.LocalDate.of(1990, 1, 1),
                    null, null, null, null, null, null,
                    "MARRIAGE", null, null, null,
                    null, null, null, null, null, null, null, null, null, null);

            service.updateProfile(userId, request);

            verify(jdbc, never()).update(
                    contains("residency_type"),
                    argThat((MapSqlParameterSource p) -> p.hasValue("residencyType")));
        }
    }
}
