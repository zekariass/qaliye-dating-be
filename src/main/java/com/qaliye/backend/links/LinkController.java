package com.qaliye.backend.links;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/links")
public class LinkController {

    private final LinkProperties properties;

    public LinkController(LinkProperties properties) {
        this.properties = properties;
    }

    @GetMapping
    public ResponseEntity<Map<String, String>> getAllLinks() {
        Map<String, String> links = new LinkedHashMap<>();
        links.put("terms", properties.getTerms());
        links.put("privacy", properties.getPrivacy());
        links.put("ios_app_store", properties.getIosAppStore());
        links.put("play_store", properties.getPlayStore());
        return ResponseEntity.ok(links);
    }

    @GetMapping(params = "key")
    public ResponseEntity<Map<String, String>> getLinkByKey(@RequestParam String key) {
        String value = switch (key.toLowerCase()) {
            case "terms" -> properties.getTerms();
            case "privacy" -> properties.getPrivacy();
            case "ios_app_store", "ios-app-store", "ios" -> properties.getIosAppStore();
            case "play_store", "play-store", "android" -> properties.getPlayStore();
            default -> null;
        };
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown_link_key: " + key);
        }
        return ResponseEntity.ok(Map.of("key", key, "url", value));
    }
}
