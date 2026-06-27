package com.qaliye.backend.user.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "profiles")
@Getter
@Setter
@NoArgsConstructor
public class Profile {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "user_id")
    private AppUser appUser;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(nullable = false)
    private String gender;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    private String bio;

    @Column(name = "height_cm")
    private Integer heightCm;

    @Column(name = "residency_type", nullable = false)
    private String residencyType;

    private String ethnicity;
    private String nationality;
    private String religion;

    @Column(name = "education_level")
    private String educationLevel;

    private String occupation;

    @Column(name = "relationship_intention")
    private String relationshipIntention;

    @Column(name = "marital_status")
    private String maritalStatus;

    @Column(name = "has_children")
    private Boolean hasChildren;

    @Column(name = "wants_children")
    private Boolean wantsChildren;

    @Column(name = "smoking")
    private Boolean smoking;

    @Column(name = "drinking")
    private Boolean drinking;

    @Column(name = "smoking_detail")
    private String smokingDetail;

    @Column(name = "drinking_detail")
    private String drinkingDetail;

    @Column(name = "activity_level")
    private String activityLevel;

    @Column(name = "interests", columnDefinition = "text[]")
    private String[] interests;

    @Column(name = "languages", columnDefinition = "text[]")
    private String[] languages;

    @Column(name = "discovery_mode", nullable = false)
    private String discoveryMode;

    @Column(name = "is_visible", nullable = false)
    private Boolean isVisible;

    @Column(name = "is_onboarded", nullable = false)
    private Boolean isOnboarded;

    @Column(name = "is_verified", nullable = false)
    private Boolean isVerified;

    @Column(name = "profile_completion_score")
    private Integer profileCompletionScore;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
