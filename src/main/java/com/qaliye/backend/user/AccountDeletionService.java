package com.qaliye.backend.user;

import com.qaliye.backend.billing.service.SubscriptionRevocationService;
import com.qaliye.backend.storage.SupabaseStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class AccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final SupabaseStorageService storageService;
    private final SupabaseAuthAdminClient authAdminClient;
    private final AuthAnonymizationTaskRepository taskRepository;
    private final CacheManager cacheManager;
    private final TransactionTemplate transactionTemplate;
    private final SubscriptionRevocationService subscriptionRevocationService;

    public AccountDeletionService(NamedParameterJdbcTemplate jdbc,
                                   SupabaseStorageService storageService,
                                   SupabaseAuthAdminClient authAdminClient,
                                   AuthAnonymizationTaskRepository taskRepository,
                                   CacheManager cacheManager,
                                   PlatformTransactionManager transactionManager,
                                   SubscriptionRevocationService subscriptionRevocationService) {
        this.jdbc = jdbc;
        this.storageService = storageService;
        this.authAdminClient = authAdminClient;
        this.taskRepository = taskRepository;
        this.cacheManager = cacheManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.subscriptionRevocationService = subscriptionRevocationService;
    }

    record StorageItem(String bucket, String path) {}

    /**
     * Permanently deletes a user account.
     *
     * <p>Phase 1 (transactional): marks the account DELETED, anonymizes profile PII, removes
     * privacy-sensitive DB rows (photos, discovery data, blocks, notifications, messages).
     *
     * <p>Phase 2 (best-effort): deletes storage objects (profile photos, verification selfies,
     * chat and support attachments).
     *
     * <p>Phase 3: evicts the userStatus cache so existing JWTs are rejected immediately.
     *
     * <p>Phase 4: soft-deletes the Supabase Auth user via the Admin API
     * ({@code DELETE /auth/v1/admin/users/{id}?should_soft_delete=true}).
     * Supabase removes all linked OAuth identities (Google, Apple …) and active sessions,
     * and anonymizes email/phone so the same person can re-register as a brand-new user.
     * This phase is best-effort: a failure is logged and a task is persisted for
     * {@link AuthAnonymizationRetryWorker} to retry with exponential backoff.
     *
     * <p>The operation is idempotent: if called again after partial failure the transactional
     * phase is skipped (already DELETED) and the remaining phases are retried.
     */
    public void deleteAccount(UUID userId) {
        List<StorageItem> itemsToDelete = runTransactionalPhase(userId);
        deleteStorageObjects(itemsToDelete);
        evictUserStatusCache(userId);
        taskRepository.insertPendingIfAbsent(userId);
        trySoftDeleteAuthUser(userId);
    }

    private void trySoftDeleteAuthUser(UUID userId) {
        try {
            authAdminClient.softDeleteAuthUser(userId);
            taskRepository.markCompleted(userId);
        } catch (Exception e) {
            log.warn("Auth soft-delete failed for {} – task persisted for worker retry: {}", userId, e.getMessage());
        }
    }

    List<StorageItem> runTransactionalPhase(UUID userId) {
        List<StorageItem> result = transactionTemplate.execute(status -> doDelete(userId));
        return result != null ? result : List.of();
    }

    private List<StorageItem> doDelete(UUID userId) {
        var params = new MapSqlParameterSource("userId", userId);

        String status = jdbc.query(
                "SELECT status FROM app_users WHERE id = :userId FOR UPDATE",
                params,
                (rs, n) -> rs.getString("status")
        ).stream().findFirst().orElse(null);

        if (status == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user_not_found");
        }

        if ("DELETED".equals(status)) {
            log.info("Account {} already deleted; skipping transactional phase", userId);
            return List.of();
        }

        // ── 0. Revoke subscriptions, entitlements, pending orders & promo reservations ─
        subscriptionRevocationService.revokeAll(userId);

        // ── 1. Mark account as deleted ──────────────────────────────────────────
        jdbc.update("""
                UPDATE app_users
                SET status     = 'DELETED',
                    deleted_at = NOW(),
                    updated_at = NOW()
                WHERE id = :userId
                """, params);

        // ── 2. Hide and anonymize profile ───────────────────────────────────────
        jdbc.update("""
                UPDATE profiles
                SET is_visible    = FALSE,
                    display_name  = 'Deleted user',
                    bio           = NULL,
                    height_cm     = NULL,
                    religion      = NULL,
                    education_level = NULL,
                    occupation    = NULL,
                    nationality   = NULL,
                    interests     = '{}',
                    updated_at    = NOW()
                WHERE user_id = :userId
                """, params);

        // ── 3. End all active matches ────────────────────────────────────────────
        jdbc.update("""
                UPDATE matches
                SET status           = 'ENDED',
                    end_reason       = 'ACCOUNT_DELETED',
                    ended_by_user_id = :userId,
                    ended_at         = NOW(),
                    updated_at       = NOW()
                WHERE (user_one_id = :userId OR user_two_id = :userId)
                  AND status = 'ACTIVE'
                """, params);

        // ── 4. Notification cleanup ──────────────────────────────────────────────
        // Delete deliveries that reference the user's devices or the user's outbox events,
        // to satisfy RESTRICT FK constraints before deleting the parent rows.
        jdbc.update("""
                DELETE FROM notification_deliveries
                WHERE notification_outbox_event_id IN (
                    SELECT id FROM notification_outbox_events WHERE recipient_user_id = :userId
                )
                   OR notification_device_id IN (
                    SELECT id FROM notification_devices WHERE user_id = :userId
                )
                """, params);

        jdbc.update("""
                DELETE FROM notification_outbox_events WHERE recipient_user_id = :userId
                """, params);

        jdbc.update("""
                DELETE FROM notification_devices WHERE user_id = :userId
                """, params);

        jdbc.update("""
                DELETE FROM user_notification_preferences WHERE user_id = :userId
                """, params);

        // ── 5. Chat and match meta-data cleanup ──────────────────────────────────
        jdbc.update("""
                DELETE FROM match_notification_settings WHERE user_id = :userId
                """, params);

        jdbc.update("""
                DELETE FROM chat_outbox_events WHERE recipient_user_id = :userId
                """, params);

        // ── 6. Discovery data cleanup ────────────────────────────────────────────
        jdbc.update("""
                DELETE FROM discovery_preferences WHERE user_id = :userId
                """, params);

        // Delete discovery actions not referenced by matches (match FKs are
        // NOT NULL + ON DELETE RESTRICT + immutable via trigger, so we must leave
        // the referenced rows in place but reverse them so they don't appear in
        // likes lists as ACTIVE).
        jdbc.update("""
                DELETE FROM user_discovery_actions
                WHERE (actor_user_id = :userId OR target_user_id = :userId)
                  AND id NOT IN (
                    SELECT user_one_like_action_id FROM matches
                    WHERE user_one_like_action_id IS NOT NULL
                    UNION
                    SELECT user_two_like_action_id FROM matches
                    WHERE user_two_like_action_id IS NOT NULL
                  )
                """, params);

        jdbc.update("""
                UPDATE user_discovery_actions
                SET status          = 'REVERSED',
                    reversed_at     = NOW(),
                    reversed_reason = 'ACCOUNT_DELETED'
                WHERE (actor_user_id = :userId OR target_user_id = :userId)
                  AND status = 'ACTIVE'
                """, params);

        jdbc.update("""
                DELETE FROM user_blocks
                WHERE blocker_user_id = :userId OR blocked_user_id = :userId
                """, params);

        // ── 7. Profile photos – collect paths then delete rows ───────────────────
        List<StorageItem> photoItems = jdbc.query("""
                SELECT storage_bucket, storage_path
                FROM profile_photos
                WHERE user_id = :userId AND storage_path IS NOT NULL
                """, params,
                (rs, n) -> new StorageItem(rs.getString("storage_bucket"), rs.getString("storage_path")));

        jdbc.update("DELETE FROM profile_photos WHERE user_id = :userId", params);

        // ── 8. Verification selfies – collect paths, keep rows for audit ─────────
        List<StorageItem> verificationItems = jdbc.query("""
                SELECT 'verification-selfies' AS bucket, storage_path
                FROM user_verifications
                WHERE user_id = :userId AND storage_path IS NOT NULL
                """, params,
                (rs, n) -> new StorageItem(rs.getString("bucket"), rs.getString("storage_path")));

        jdbc.update("""
                UPDATE user_verifications
                SET storage_path = NULL
                WHERE user_id = :userId
                """, params);

        // ── 9. Chat messages – anonymize body, collect and delete attachments ────
        List<StorageItem> chatAttachmentItems = jdbc.query("""
                SELECT ca.storage_bucket, ca.storage_path
                FROM chat_attachments ca
                JOIN messages m ON m.id = ca.message_id
                WHERE m.sender_user_id = :userId AND ca.storage_path IS NOT NULL
                """, params,
                (rs, n) -> new StorageItem(rs.getString("storage_bucket"), rs.getString("storage_path")));

        jdbc.update("""
                DELETE FROM chat_attachments
                WHERE message_id IN (
                    SELECT id FROM messages WHERE sender_user_id = :userId
                )
                """, params);

        // Only anonymize the body. storage_bucket/storage_path cannot be nulled for
        // IMAGE/VOICE rows (check_message_content_by_type constraint requires them NOT NULL).
        // Physical files are already collected from chat_attachments above and will be
        // deleted in Phase 2.
        jdbc.update("""
                UPDATE messages
                SET body       = '[deleted]',
                    updated_at = NOW()
                WHERE sender_user_id = :userId
                  AND deleted_at IS NULL
                """, params);

        // ── 10. Support messages – anonymize body, collect and delete attachments ─
        List<StorageItem> supportAttachmentItems = jdbc.query("""
                SELECT sa.storage_bucket, sa.storage_path
                FROM support_attachments sa
                JOIN support_messages sm ON sm.id = sa.message_id
                WHERE sm.sender_user_id = :userId AND sm.sender_type = 'USER'
                  AND sa.storage_path IS NOT NULL
                """, params,
                (rs, n) -> new StorageItem(rs.getString("storage_bucket"), rs.getString("storage_path")));

        jdbc.update("""
                DELETE FROM support_attachments
                WHERE message_id IN (
                    SELECT id FROM support_messages
                    WHERE sender_user_id = :userId AND sender_type = 'USER'
                )
                """, params);

        jdbc.update("""
                UPDATE support_messages
                SET body = '[deleted]'
                WHERE sender_user_id = :userId AND sender_type = 'USER'
                """, params);

        // ── 11. Audit log ────────────────────────────────────────────────────────
        jdbc.update("""
                INSERT INTO audit_log (actor_user_id, action, target_table, target_id, details)
                VALUES (NULL, 'ACCOUNT_DELETED', 'app_users', :userId, '{}')
                """, params);

        log.info("Transactional deletion phase complete for user {}", userId);

        List<StorageItem> all = new ArrayList<>();
        all.addAll(photoItems);
        all.addAll(verificationItems);
        all.addAll(chatAttachmentItems);
        all.addAll(supportAttachmentItems);
        return all;
    }

    private void deleteStorageObjects(List<StorageItem> items) {
        for (StorageItem item : items) {
            try {
                storageService.deleteObject(item.bucket(), item.path());
            } catch (Exception e) {
                log.warn("Failed to delete storage object {}/{}: {}", item.bucket(), item.path(), e.getMessage());
            }
        }
    }

    private void evictUserStatusCache(UUID userId) {
        try {
            var cache = cacheManager.getCache("userStatus");
            if (cache != null) {
                cache.evict(userId);
            }
        } catch (Exception e) {
            log.warn("Failed to evict userStatus cache for {}: {}", userId, e.getMessage());
        }
    }
}
