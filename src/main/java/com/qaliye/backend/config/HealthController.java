package com.qaliye.backend.config;

import com.qaliye.backend.common.CallerUtils;
import com.qaliye.backend.user.UserStatusService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
public class HealthController {

    @Value("${supabase.url}")
    private String supabaseUrl;

    @Value("${supabase.service-role-key}")
    private String serviceRoleKey;

    private final NamedParameterJdbcTemplate jdbc;
    private final RestClient restClient;
    private final List<RequestMappingHandlerMapping> handlerMappings;
    private final UserStatusService userStatusService;

    public HealthController(NamedParameterJdbcTemplate jdbc,
                            RestClient restClient,
                            List<RequestMappingHandlerMapping> handlerMappings,
                            UserStatusService userStatusService) {
        this.jdbc = jdbc;
        this.restClient = restClient;
        this.handlerMappings = handlerMappings;
        this.userStatusService = userStatusService;
    }

    @GetMapping("/api/v1/health")
    public ResponseEntity<Map<String, Object>> health() {
        String dbStatus = checkDatabase();
        String storageStatus = checkStorage();
        boolean allUp = "UP".equals(dbStatus) && "UP".equals(storageStatus);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", allUp ? "UP" : "DOWN");
        body.put("database", dbStatus);
        body.put("supabase_storage", storageStatus);

        return ResponseEntity.status(allUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(body);
    }

    @GetMapping("/api/v1/routes")
    public ResponseEntity<List<String>> routes() {
        UUID callerId = CallerUtils.callerId();
        UserStatusService.UserStatus status = userStatusService.getStatus(callerId);
        if (status == null || !"ADMIN".equals(status.role())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        List<String> routes = handlerMappings.stream()
                .flatMap(m -> m.getHandlerMethods().keySet().stream())
                .map(Object::toString)
                .sorted()
                .collect(Collectors.toList());

        return ResponseEntity.ok(routes);
    }

    private String checkDatabase() {
        try {
            jdbc.queryForObject("SELECT 1", Map.of(), Integer.class);
            return "UP";
        } catch (Exception e) {
            return "DOWN";
        }
    }

    private String checkStorage() {
        try {
            ResponseEntity<Void> response = restClient.get()
                    .uri(supabaseUrl + "/storage/v1/bucket")
                    .header("Authorization", "Bearer " + serviceRoleKey)
                    .retrieve()
                    .toBodilessEntity();
            return response.getStatusCode().is2xxSuccessful() ? "UP" : "DOWN";
        } catch (Exception e) {
            return "DOWN";
        }
    }
}
