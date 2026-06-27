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

    @Column(name = "max_age", nullable = false)
    private Integer maxAge;

    @Column(name = "max_distance_km", nullable = false)
    private Integer maxDistanceKm;

    @Column(name = "open_to_long_distance", nullable = false)
    private Boolean openToLongDistance;

    @Column(name = "open_to_relocation", nullable = false)
    private Boolean openToRelocation;

    @Column(name = "show_verified_only", nullable = false)
    private Boolean showVerifiedOnly;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
