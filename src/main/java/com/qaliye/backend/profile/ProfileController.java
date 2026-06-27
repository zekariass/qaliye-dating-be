package com.qaliye.backend.profile;

import com.qaliye.backend.common.CallerUtils;
import com.qaliye.backend.profile.dto.DiscoveryPreferencesDto;
import com.qaliye.backend.profile.dto.OtherUserProfileDto;
import com.qaliye.backend.profile.dto.PhotoRegistrationRequest;
import com.qaliye.backend.profile.dto.PhotoReorderRequest;
import com.qaliye.backend.profile.dto.ProfileLocationDto;
import com.qaliye.backend.profile.dto.ProfileMeDto;
import com.qaliye.backend.profile.dto.ProfilePhotoDto;
import com.qaliye.backend.profile.dto.ProfilePhotosResponse;
import com.qaliye.backend.profile.dto.ProfileUpdateRequest;
import com.qaliye.backend.profile.dto.VisibilityUpdateRequest;
import com.qaliye.backend.profile.dto.VisibilityUpdateResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/profile")
public class ProfileController {

    private final ProfileService profileService;
    private final ProfilePhotoService profilePhotoService;

    public ProfileController(ProfileService profileService,
                             ProfilePhotoService profilePhotoService) {
        this.profileService = profileService;
        this.profilePhotoService = profilePhotoService;
    }

    @GetMapping("/me")
    public ResponseEntity<ProfileMeDto> getProfile() {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(profileService.getCurrentProfile(callerId));
    }

    @PutMapping("/me")
    public ResponseEntity<ProfileMeDto> updateProfile(
            @Valid @RequestBody ProfileUpdateRequest request) {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(profileService.updateProfile(callerId, request));
    }

    @GetMapping("/me/photos")
    public ResponseEntity<ProfilePhotosResponse> getMyPhotos() {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(profilePhotoService.getPhotos(callerId));
    }

    @PostMapping("/me/photos")
    public ResponseEntity<ProfilePhotoDto> registerPhoto(
            @Valid @RequestBody PhotoRegistrationRequest request) {
        UUID callerId = CallerUtils.callerId();
        ProfilePhotoDto photo = profilePhotoService.registerPhoto(callerId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(photo);
    }

    @PutMapping("/me/photos")
    public ResponseEntity<ProfilePhotosResponse> reorderPhotos(
            @Valid @RequestBody PhotoReorderRequest request) {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(profilePhotoService.reorderPhotos(callerId, request));
    }

    @DeleteMapping("/me/photos/{photoId}")
    public ResponseEntity<ProfilePhotosResponse> deletePhoto(@PathVariable UUID photoId) {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(profilePhotoService.deletePhoto(callerId, photoId));
    }

    @GetMapping("/me/preferences")
    public ResponseEntity<DiscoveryPreferencesDto> getPreferences() {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(profileService.getPreferences(callerId));
    }

    @PutMapping("/me/preferences")
    public ResponseEntity<DiscoveryPreferencesDto> updatePreferences(
            @RequestBody DiscoveryPreferencesDto request) {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(profileService.updatePreferences(callerId, request));
    }

    @GetMapping("/location")
    public ResponseEntity<ProfileLocationDto> getLocation() {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(profileService.getLocation(callerId));
    }

    @PutMapping("/location")
    public ResponseEntity<ProfileLocationDto> setLocation(
            @Valid @RequestBody SetLocationRequest request) {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(profileService.setLocation(callerId, request));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<OtherUserProfileDto> getOtherProfile(@PathVariable UUID userId) {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(profileService.getOtherUserProfile(callerId, userId));
    }

    @PatchMapping("/me/visibility")
    public ResponseEntity<VisibilityUpdateResponse> updateVisibility(
            @Valid @RequestBody VisibilityUpdateRequest request) {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(profileService.updateVisibility(callerId, Boolean.TRUE.equals(request.isVisible())));
    }
}
