package com.qaliye.backend.discovery.entity;

import com.qaliye.backend.user.entity.AppUser;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "discovery_preferences")
@Getter
@Setter
@NoArgsConstructor
public class DiscoveryPreferences {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "user_id")
    private AppUser appUser;

    @Column(name = "preferred_residency_types", columnDefinition = "text[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private String[] preferredResidencyTypes;

    @Column(name = "interested_in_gender", nullable = false)
    private String interestedInGender;

    @Column(name = "min_age", nullable = false)
    private Integer minAge;

    @Column(name = "max_age")
    private Integer maxAge;

    @Column(name = "max_distance_km")
    private Integer maxDistanceKm;

    @Column(name = "show_verified_only", nullable = false)
    private Boolean showVerifiedOnly;

    @Column(name = "location_mode", nullable = false)
    private String locationMode;

    @Column(name = "specific_country_codes", columnDefinition = "text[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private String[] specificCountryCodes;

    @Column(name = "expand_search_when_limited", nullable = false)
    private Boolean expandSearchWhenLimited;

    @Column(name = "has_children_preference", nullable = false)
    private String hasChildrenPreference;

    @Column(name = "wants_children_preference", nullable = false)
    private String wantsChildrenPreference;

    @Column(name = "religion_preferences", columnDefinition = "text[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private String[] religionPreferences;

    @Column(name = "language_preference_ids", columnDefinition = "uuid[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private UUID[] languagePreferenceIds;

    @Column(name = "ethnicity_preference_ids", columnDefinition = "uuid[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private UUID[] ethnicityPreferenceIds;

    @Column(name = "preferences_version", nullable = false)
    private Integer preferencesVersion;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
