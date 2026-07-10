package com.qaliye.backend.catalog;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/languages")
    public ResponseEntity<List<LanguageOption>> listLanguages(
            @RequestParam(required = false) String countryCode,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return ResponseEntity.ok(
                catalogService.getActiveLanguages(countryCode, q, limit, offset));
    }

    @GetMapping("/ethnicities")
    public ResponseEntity<List<EthnicityOption>> listEthnicities(
            @RequestParam(required = false) String countryCode,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return ResponseEntity.ok(
                catalogService.getActiveEthnicities(countryCode, q, limit, offset));
    }
}
