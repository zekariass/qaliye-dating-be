package com.qaliye.backend.profile;

import com.qaliye.backend.common.CallerUtils;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/profile")
public class ProfileController {

    private final ProfileService profileService;
    private final PhotoService photoService;

    public ProfileController(ProfileService profileService, PhotoService photoService) {
        this.profileService = profileService;
        this.photoService = photoService;
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getProfile() {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(profileService.getProfile(callerId));
    }

    @PutMapping("/me")
    public ResponseEntity<Map<String, Object>> updateProfile(
            @Valid @RequestBody UpdateProfileRequest request) {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(profileService.updateProfile(callerId, request));
    }

    @GetMapping("/location")
    public ResponseEntity<Map<String, Object>> getLocation() {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(profileService.getLocation(callerId));
    }

    @PutMapping("/location")
    public ResponseEntity<Map<String, Object>> setLocation(
            @Valid @RequestBody SetLocationRequest request) {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(profileService.setLocation(callerId, request));
    }

    @DeleteMapping("/location")
    public ResponseEntity<Map<String, Object>> deleteLocation() {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(profileService.deleteLocation(callerId));
    }

    @PostMapping(value = "/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadPhoto(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "photo_order", defaultValue = "0") int photoOrder,
            @RequestParam(value = "is_primary", defaultValue = "true") boolean isPrimary)
            throws IOException {
        UUID callerId = CallerUtils.callerId();
        Map<String, Object> result = photoService.uploadPhoto(callerId, file, photoOrder, isPrimary);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @GetMapping("/photos/me")
    public ResponseEntity<Map<String, Object>> getMyPhotos() {
        UUID callerId = CallerUtils.callerId();
        List<Map<String, Object>> items = photoService.getMyPhotos(callerId);
        return ResponseEntity.ok(Map.of("items", items));
    }

    @PutMapping(value = "/photos/primary", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> replacePrimaryPhoto(
            @RequestParam("file") MultipartFile file) throws IOException {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(photoService.replacePrimaryPhoto(callerId, file));
    }

    @PatchMapping("/photos/{photoId}")
    public ResponseEntity<Map<String, Object>> patchPhoto(
            @PathVariable UUID photoId,
            @Valid @RequestBody PatchPhotoRequest request) {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(photoService.patchPhoto(callerId, photoId, request));
    }

    @DeleteMapping("/photos/{photoId}")
    public ResponseEntity<Map<String, Object>> deletePhoto(@PathVariable UUID photoId) {
        UUID callerId = CallerUtils.callerId();
        return ResponseEntity.ok(photoService.deletePhoto(callerId, photoId));
    }
}
