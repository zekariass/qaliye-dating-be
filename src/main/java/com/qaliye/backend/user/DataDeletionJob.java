package com.qaliye.backend.user;

import com.qaliye.backend.storage.SupabaseStorageService;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class DataDeletionJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(DataDeletionJob.class);

    private static final String FIND_SOFT_DELETED_SQL = """
            SELECT id FROM app_users
            WHERE deleted_at IS NOT NULL
              AND deleted_at < NOW() - INTERVAL '30 days'
            """;

    private static final String ANONYMIZE_PROFILE_SQL = """
            UPDATE profiles SET
              display_name = 'Deleted User',
              bio = NULL,
              height_cm = NULL,
              ethnicity = NULL,
              nationality = NULL,
              religion = NULL,
              education_level = NULL,
              occupation = NULL,
              is_visible = FALSE
            WHERE user_id = :userId
            """;

    private static final String FETCH_PHOTO_PATHS_SQL =
            "SELECT storage_path FROM profile_photos WHERE user_id = :userId";

    private static final String DELETE_PROFILE_PHOTOS_SQL =
            "DELETE FROM profile_photos WHERE user_id = :userId";

    private static final String ANONYMIZE_MESSAGES_SQL = """
            UPDATE messages SET body = '[deleted]', media_url = NULL, storage_path = NULL
            WHERE sender_user_id = :userId
            """;

    private static final String AUDIT_SQL = """
            INSERT INTO audit_log (actor_user_id, action, target_table, target_id, details)
            VALUES (NULL, 'ACCOUNT_ANONYMIZED', 'app_users', :userId, '{}')
            """;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private SupabaseStorageService storageService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        log.info("DataDeletionJob starting");
        List<UUID> userIds = jdbc.query(FIND_SOFT_DELETED_SQL, Map.of(),
                (rs, rowNum) -> rs.getObject("id", UUID.class));

        log.info("DataDeletionJob: {} account(s) eligible for anonymization", userIds.size());

        for (UUID userId : userIds) {
            try {
                anonymizeUser(userId);
                log.info("Anonymized account: {}", userId);
            } catch (Exception e) {
                log.error("Failed to anonymize user {}: {}", userId, e.getMessage());
            }
        }
    }

    private void anonymizeUser(UUID userId) {
        MapSqlParameterSource params = new MapSqlParameterSource("userId", userId);

        List<String> storagePaths = jdbc.queryForList(FETCH_PHOTO_PATHS_SQL, params, String.class);
        for (String path : storagePaths) {
            storageService.deleteObject("profile-photos", path);
        }

        new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.update(ANONYMIZE_PROFILE_SQL, params);
            jdbc.update(DELETE_PROFILE_PHOTOS_SQL, params);
            jdbc.update(ANONYMIZE_MESSAGES_SQL, params);
            jdbc.update(AUDIT_SQL, params);
            return null;
        });
    }
}
