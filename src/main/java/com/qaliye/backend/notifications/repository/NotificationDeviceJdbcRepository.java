package com.qaliye.backend.notifications.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class NotificationDeviceJdbcRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationDeviceJdbcRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record DeviceRow(
            UUID id,
            UUID userId,
            String deviceToken,
            String platform,
            boolean isActive,
            UUID installationId,
            String appEnvironment,
            OffsetDateTime disabledAt,
            String lastErrorCode,
            OffsetDateTime lastErrorAt
    ) {}

    private static final String LOCK_BY_TOKEN_SQL = """
            SELECT id, user_id, device_token, platform, is_active,
                   installation_id, app_environment, disabled_at,
                   last_error_code, last_error_at
            FROM notification_devices
            WHERE device_token = :token
            FOR UPDATE
            """;

    private static final String LOCK_BY_ID_SQL = """
            SELECT id, user_id, device_token, platform, is_active,
                   installation_id, app_environment, disabled_at,
                   last_error_code, last_error_at
            FROM notification_devices
            WHERE id = :id
            FOR UPDATE
            """;

    private static final String FIND_BY_ID_SQL = """
            SELECT id, user_id, device_token, platform, is_active,
                   installation_id, app_environment, disabled_at,
                   last_error_code, last_error_at
            FROM notification_devices
            WHERE id = :id
            """;

    private static final String MARK_DEVICE_NOT_REGISTERED_BY_ID_SQL = """
            UPDATE notification_devices
            SET is_active       = FALSE,
                disabled_at     = NOW(),
                last_error_code = :errorCode,
                last_error_at   = NOW(),
                updated_at      = NOW()
            WHERE id = :id
            """;

    private static final String UPSERT_SQL = """
            INSERT INTO notification_devices
                (id, user_id, device_token, platform, is_active,
                 installation_id, app_environment, disabled_at,
                 last_error_code, last_error_at, last_seen_at,
                 created_at, updated_at)
            VALUES
                (:id, :userId, :token, :platform, TRUE,
                 :installationId, :appEnvironment, NULL,
                 NULL, NULL, NOW(),
                 NOW(), NOW())
            ON CONFLICT (device_token) DO UPDATE
            SET user_id          = :userId,
                is_active        = TRUE,
                installation_id  = :installationId,
                app_environment  = :appEnvironment,
                disabled_at      = NULL,
                last_error_code  = NULL,
                last_error_at    = NULL,
                last_seen_at     = NOW(),
                updated_at       = NOW()
            """;

    private static final String DEACTIVATE_OTHER_ACTIVE_FOR_INSTALLATION_SQL = """
            UPDATE notification_devices
            SET is_active   = FALSE,
                disabled_at = NOW(),
                updated_at  = NOW()
            WHERE installation_id = :installationId
              AND app_environment  = :appEnvironment
              AND device_token    <> :currentToken
              AND is_active = TRUE
            """;

    private static final String DEACTIVATE_BY_TOKEN_AND_USER_SQL = """
            UPDATE notification_devices
            SET is_active   = FALSE,
                disabled_at = NOW(),
                updated_at  = NOW()
            WHERE device_token     = :token
              AND user_id          = :userId
              AND installation_id  = :installationId
              AND is_active = TRUE
            """;

    private static final String MARK_DEVICE_NOT_REGISTERED_SQL = """
            UPDATE notification_devices
            SET is_active       = FALSE,
                disabled_at     = NOW(),
                last_error_code = :errorCode,
                last_error_at   = NOW(),
                updated_at      = NOW()
            WHERE device_token = :token
            """;

    private static final String ACTIVE_DEVICES_FOR_USER_SQL = """
            SELECT id, user_id, device_token, platform, is_active,
                   installation_id, app_environment, disabled_at,
                   last_error_code, last_error_at
            FROM notification_devices
            WHERE user_id = :userId
              AND is_active = TRUE
              AND app_environment = :appEnvironment
            """;

    private static final String DEACTIVATE_ALL_FOR_USER_SQL = """
            UPDATE notification_devices
            SET is_active   = FALSE,
                disabled_at = NOW(),
                updated_at  = NOW()
            WHERE user_id   = :userId
              AND is_active  = TRUE
            """;

    public Optional<DeviceRow> lockByToken(String token) {
        var params = new MapSqlParameterSource("token", token);
        List<DeviceRow> rows = jdbc.query(LOCK_BY_TOKEN_SQL, params, this::mapRow);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<DeviceRow> lockById(UUID id) {
        var params = new MapSqlParameterSource("id", id);
        List<DeviceRow> rows = jdbc.query(LOCK_BY_ID_SQL, params, this::mapRow);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<DeviceRow> findById(UUID id) {
        var params = new MapSqlParameterSource("id", id);
        List<DeviceRow> rows = jdbc.query(FIND_BY_ID_SQL, params, this::mapRow);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void markDeviceNotRegisteredById(UUID id, String errorCode) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("errorCode", errorCode);
        jdbc.update(MARK_DEVICE_NOT_REGISTERED_BY_ID_SQL, params);
    }

    public void upsert(UUID id, UUID userId, String token, String platform,
                       UUID installationId, String appEnvironment) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("userId", userId)
                .addValue("token", token)
                .addValue("platform", platform)
                .addValue("installationId", installationId)
                .addValue("appEnvironment", appEnvironment);
        jdbc.update(UPSERT_SQL, params);
    }

    public void deactivateOtherActiveForInstallation(UUID installationId,
                                                     String appEnvironment,
                                                     String currentToken) {
        var params = new MapSqlParameterSource()
                .addValue("installationId", installationId)
                .addValue("appEnvironment", appEnvironment)
                .addValue("currentToken", currentToken);
        jdbc.update(DEACTIVATE_OTHER_ACTIVE_FOR_INSTALLATION_SQL, params);
    }

    public void deactivateByTokenAndUser(String token, UUID userId, UUID installationId) {
        var params = new MapSqlParameterSource()
                .addValue("token", token)
                .addValue("userId", userId)
                .addValue("installationId", installationId);
        jdbc.update(DEACTIVATE_BY_TOKEN_AND_USER_SQL, params);
    }

    public void markDeviceNotRegistered(String token, String errorCode) {
        var params = new MapSqlParameterSource()
                .addValue("token", token)
                .addValue("errorCode", errorCode);
        jdbc.update(MARK_DEVICE_NOT_REGISTERED_SQL, params);
    }

    public List<DeviceRow> findActiveDevicesForUser(UUID userId, String appEnvironment) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("appEnvironment", appEnvironment);
        return jdbc.query(ACTIVE_DEVICES_FOR_USER_SQL, params, this::mapRow);
    }

    public void deactivateAllForUser(UUID userId) {
        jdbc.update(DEACTIVATE_ALL_FOR_USER_SQL, new MapSqlParameterSource("userId", userId));
    }

    private DeviceRow mapRow(ResultSet rs, int n) throws SQLException {
        return new DeviceRow(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("device_token"),
                rs.getString("platform"),
                rs.getBoolean("is_active"),
                rs.getObject("installation_id", UUID.class),
                rs.getString("app_environment"),
                rs.getObject("disabled_at", OffsetDateTime.class),
                rs.getString("last_error_code"),
                rs.getObject("last_error_at", OffsetDateTime.class)
        );
    }
}
