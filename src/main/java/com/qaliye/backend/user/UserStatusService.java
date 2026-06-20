package com.qaliye.backend.user;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class UserStatusService {

    public record UserStatus(String status, String role, String preferredLanguage) {}

    private final NamedParameterJdbcTemplate jdbc;

    public UserStatusService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Cacheable(value = "userStatus", key = "#userId")
    public UserStatus getStatus(UUID userId) {
        List<UserStatus> results = query(userId);
        if (!results.isEmpty()) return results.get(0);

        // Auto-provision new user with DB defaults
        try {
            jdbc.update("INSERT INTO app_users (id) VALUES (:userId)", Map.of("userId", userId));
        } catch (DuplicateKeyException e) {
            results = query(userId);
            return results.isEmpty() ? null : results.get(0);
        }

        results = query(userId);
        return results.isEmpty() ? null : results.get(0);
    }

    private List<UserStatus> query(UUID userId) {
        return jdbc.query(
                "SELECT status, role, preferred_language FROM app_users WHERE id = :userId",
                Map.of("userId", userId),
                (rs, rowNum) -> new UserStatus(
                        rs.getString("status"),
                        rs.getString("role"),
                        rs.getString("preferred_language"))
        );
    }
}
