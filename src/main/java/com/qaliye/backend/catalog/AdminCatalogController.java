package com.qaliye.backend.catalog;

import com.qaliye.backend.catalog.dto.CreateEthnicityRequest;
import com.qaliye.backend.catalog.dto.CreateLanguageRequest;
import com.qaliye.backend.catalog.dto.UpdateEthnicityRequest;
import com.qaliye.backend.catalog.dto.UpdateLanguageRequest;
import com.qaliye.backend.user.UserStatusService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/catalog")
public class AdminCatalogController {

    private final CatalogService catalogService;
    private final UserStatusService userStatusService;

    public AdminCatalogController(CatalogService catalogService,
                                   UserStatusService userStatusService) {
        this.catalogService = catalogService;
        this.userStatusService = userStatusService;
    }

    // -------------------------------------------------------------------------
    // Languages
    // -------------------------------------------------------------------------

    @GetMapping("/languages")
    public ResponseEntity<List<LanguageOption>> listLanguages(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String countryCode,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        requireAdmin(jwt);
        return ResponseEntity.ok(catalogService.adminListLanguages(countryCode, limit, offset));
    }

    @PostMapping("/languages")
    public ResponseEntity<LanguageOption> createLanguage(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateLanguageRequest body) {
        requireAdmin(jwt);
        LanguageOption created = catalogService.adminCreateLanguage(
                body.code(), body.countryCode(), body.name(), body.nativeName(), body.sortOrder());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/languages/{id}")
    public ResponseEntity<LanguageOption> updateLanguage(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateLanguageRequest body) {
        requireAdmin(jwt);
        return ResponseEntity.ok(catalogService.adminUpdateLanguage(
                id, body.name(), body.nativeName(), body.isActive(), body.sortOrder()));
    }

    @DeleteMapping("/languages/{id}")
    public ResponseEntity<Void> deleteLanguage(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        requireAdmin(jwt);
        catalogService.adminSoftDeleteLanguage(id);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // Ethnicities
    // -------------------------------------------------------------------------

    @GetMapping("/ethnicities")
    public ResponseEntity<List<EthnicityOption>> listEthnicities(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String countryCode,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        requireAdmin(jwt);
        return ResponseEntity.ok(catalogService.adminListEthnicities(countryCode, limit, offset));
    }

    @PostMapping("/ethnicities")
    public ResponseEntity<EthnicityOption> createEthnicity(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateEthnicityRequest body) {
        requireAdmin(jwt);
        EthnicityOption created = catalogService.adminCreateEthnicity(
                body.code(), body.countryCode(), body.name(), body.region(), body.sortOrder());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/ethnicities/{id}")
    public ResponseEntity<EthnicityOption> updateEthnicity(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateEthnicityRequest body) {
        requireAdmin(jwt);
        return ResponseEntity.ok(catalogService.adminUpdateEthnicity(
                id, body.name(), body.region(), body.isActive(), body.sortOrder()));
    }

    @DeleteMapping("/ethnicities/{id}")
    public ResponseEntity<Void> deleteEthnicity(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        requireAdmin(jwt);
        catalogService.adminSoftDeleteEthnicity(id);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------

    private void requireAdmin(Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        UserStatusService.UserStatus status = userStatusService.getStatus(userId);
        if (status == null || !"ADMIN".equals(status.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required.");
        }
    }
}
