package com.qaliye.backend.profile.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record ProfileUpdateRequest(
        @Size(min = 2, max = 50) String displayName,
        @Pattern(regexp = "MALE|FEMALE") String gender,
        LocalDate dateOfBirth,
        @Min(100) @Max(250) Integer heightCm,
        @Pattern(regexp = "ETHIOPIA|ERITREA|DIASPORA") String residencyType,
        @Size(max = 2000) String bio,
        String ethnicity,
        String nationality,
        String religion,
        String educationLevel,
        @Size(max = 100) String occupation,
        @Pattern(regexp = "MARRIAGE|SERIOUS_RELATIONSHIP|LONG_TERM|FRIENDSHIP|NOT_SURE_YET") String relationshipIntention,
        String maritalStatus,
        Boolean hasChildren,
        Boolean wantsChildren,
        Boolean smoking,
        Boolean drinking,
        @Pattern(regexp = "NO|YES|OCCASIONALLY|TRYING_TO_QUIT") String smokingDetail,
        @Pattern(regexp = "NO|SOCIALLY|OCCASIONALLY|YES") String drinkingDetail,
        @Pattern(regexp = "SEDENTARY|LIGHT|MODERATE|ACTIVE|VERY_ACTIVE") String activityLevel,
        @Size(max = 20) List<String> interests,
        @Size(max = 20) List<String> languages,
        @Pattern(regexp = "PUBLIC|INCOGNITO") String discoveryMode
) {}
