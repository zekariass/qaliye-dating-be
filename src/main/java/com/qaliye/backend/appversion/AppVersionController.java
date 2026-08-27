package com.qaliye.backend.appversion;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/app")
public class AppVersionController {

    private final AppVersionService appVersionService;

    public AppVersionController(AppVersionService appVersionService) {
        this.appVersionService = appVersionService;
    }

    @GetMapping("/version")
    public ResponseEntity<AppVersionResponse> getVersion(@RequestParam String platform) {
        return ResponseEntity.ok(appVersionService.getVersion(platform));
    }
}
