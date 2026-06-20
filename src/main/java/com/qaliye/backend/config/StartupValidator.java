package com.qaliye.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
public class StartupValidator {

    private static final Logger log = LoggerFactory.getLogger(StartupValidator.class);

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
    private String jwksUrl;

    private final NamedParameterJdbcTemplate jdbc;
    private final RestClient restClient;

    public StartupValidator(NamedParameterJdbcTemplate jdbc,
                            @Lazy RestClient restClient) {
        this.jdbc = jdbc;
        this.restClient = restClient;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        checkJwks();
        checkDatabase();
        checkRequiredTables();
        checkUserVerificationsColumn();
        checkFreePlan();
    }

    @SuppressWarnings("unchecked")
    private void checkJwks() {
        try {
            Map<String, Object> response = restClient.get()
                    .uri(jwksUrl)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            List<?> keys = response != null ? (List<?>) response.get("keys") : null;
            log.info("[Startup] JWKS endpoint reachable: {} — {} key(s)",
                    jwksUrl, keys != null ? keys.size() : 0);
        } catch (Exception e) {
            log.warn("[Startup] JWKS endpoint NOT reachable: {} — {}", jwksUrl, e.getMessage());
        }
    }

    private void checkDatabase() {
        try {
            Map<String, Object> result = jdbc.queryForMap(
                    "SELECT current_database() AS db, version() AS ver", Map.of());
            log.info("[Startup] Database reachable: db={}, version={}",
                    result.get("db"), result.get("ver"));
        } catch (Exception e) {
            log.warn("[Startup] Database NOT reachable: {}", e.getMessage());
        }
    }

    private void checkRequiredTables() {
        List<String> required = List.of(
                "app_users", "profiles", "matches", "messages", "user_verifications");
        try {
            List<String> existing = jdbc.queryForList("""
                    SELECT table_name FROM information_schema.tables
                    WHERE table_schema = 'public' AND table_name IN (:tables)
                    """, Map.of("tables", required), String.class);
            List<String> missing = required.stream()
                    .filter(t -> !existing.contains(t))
                    .toList();
            if (missing.isEmpty()) {
                log.info("[Startup] All required tables present");
            } else {
                log.warn("[Startup] Missing tables: {}", missing);
            }
        } catch (Exception e) {
            log.warn("[Startup] Could not check required tables: {}", e.getMessage());
        }
    }

    private void checkUserVerificationsColumn() {
        try {
            List<String> cols = jdbc.queryForList("""
                    SELECT column_name FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = 'user_verifications'
                      AND column_name = 'storage_path'
                    """, Map.of(), String.class);
            if (cols.isEmpty()) {
                log.warn("[Startup] user_verifications.storage_path missing — V1 migration not applied");
            } else {
                log.info("[Startup] user_verifications.storage_path column present");
            }
        } catch (Exception e) {
            log.warn("[Startup] Could not check user_verifications.storage_path: {}", e.getMessage());
        }
    }

    private void checkFreePlan() {
        try {
            List<String> plans = jdbc.queryForList(
                    "SELECT plan_code FROM subscription_plans WHERE plan_code = 'FREE'",
                    Map.of(), String.class);
            if (plans.isEmpty()) {
                log.warn("[Startup] No 'FREE' plan in subscription_plans — seed data not applied");
            } else {
                log.info("[Startup] 'FREE' subscription plan found");
            }
        } catch (Exception e) {
            log.warn("[Startup] Could not check subscription_plans: {}", e.getMessage());
        }
    }
}
