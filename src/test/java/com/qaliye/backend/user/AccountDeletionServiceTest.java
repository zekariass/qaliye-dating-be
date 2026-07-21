package com.qaliye.backend.user;

import com.qaliye.backend.billing.service.SubscriptionRevocationService;
import com.qaliye.backend.storage.SupabaseStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountDeletionServiceTest {

    @Mock NamedParameterJdbcTemplate jdbc;
    @Mock SupabaseStorageService storageService;
    @Mock SupabaseAuthAdminClient authAdminClient;
    @Mock AuthAnonymizationTaskRepository taskRepository;
    @Mock CacheManager cacheManager;
    @Mock Cache cache;
    @Mock PlatformTransactionManager transactionManager;
    @Mock TransactionStatus transactionStatus;
    @Mock SubscriptionRevocationService subscriptionRevocationService;

    AccountDeletionService service;

    UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        service = new AccountDeletionService(jdbc, storageService, authAdminClient, taskRepository, cacheManager, transactionManager, subscriptionRevocationService);
        lenient().when(cacheManager.getCache("userStatus")).thenReturn(cache);
    }

    /**
     * Stubs all jdbc.query calls: status queries return the given status,
     * all storage-path collector queries return an empty list.
     */
    private void stubQueryCalls(String status) {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    String sql = (String) inv.getArgument(0);
                    if (sql.contains("SELECT status FROM app_users")) {
                        RowMapper<String> mapper = inv.getArgument(2);
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getString("status")).thenReturn(status);
                        return List.of(mapper.mapRow(rs, 0));
                    }
                    return List.of();
                });
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void deleteAccount_activeUser_marksDeleted() {
        stubQueryCalls("ACTIVE");

        service.deleteAccount(userId);

        verify(jdbc).update(contains("status     = 'DELETED'"), any(MapSqlParameterSource.class));
    }

    @Test
    void deleteAccount_activeUser_revokesSubscriptions() {
        stubQueryCalls("ACTIVE");

        service.deleteAccount(userId);

        verify(subscriptionRevocationService).revokeAll(userId);
    }

    @Test
    void deleteAccount_activeUser_anonymizesProfile() {
        stubQueryCalls("ACTIVE");

        service.deleteAccount(userId);

        verify(jdbc).update(contains("display_name  = 'Deleted user'"), any(MapSqlParameterSource.class));
    }

    @Test
    void deleteAccount_activeUser_endsActiveMatches() {
        stubQueryCalls("ACTIVE");

        service.deleteAccount(userId);

        verify(jdbc).update(contains("end_reason       = 'ACCOUNT_DELETED'"), any(MapSqlParameterSource.class));
    }

    @Test
    void deleteAccount_activeUser_cleansUpNotifications() {
        stubQueryCalls("ACTIVE");

        service.deleteAccount(userId);

        verify(jdbc).update(contains("DELETE FROM notification_deliveries"), any(MapSqlParameterSource.class));
        verify(jdbc).update(contains("DELETE FROM notification_outbox_events"), any(MapSqlParameterSource.class));
        verify(jdbc).update(contains("DELETE FROM notification_devices"), any(MapSqlParameterSource.class));
        verify(jdbc).update(contains("user_notification_preferences"), any(MapSqlParameterSource.class));
    }

    @Test
    void deleteAccount_activeUser_cleansUpDiscoveryAndBlocks() {
        stubQueryCalls("ACTIVE");

        service.deleteAccount(userId);

        verify(jdbc).update(contains("discovery_preferences"), any(MapSqlParameterSource.class));
        verify(jdbc).update(contains("user_discovery_actions"), any(MapSqlParameterSource.class));
        verify(jdbc).update(contains("user_blocks"), any(MapSqlParameterSource.class));
    }

    @Test
    void deleteAccount_activeUser_deletesProfilePhotoRows() {
        stubQueryCalls("ACTIVE");

        service.deleteAccount(userId);

        verify(jdbc).update(contains("DELETE FROM profile_photos"), any(MapSqlParameterSource.class));
    }

    @Test
    void deleteAccount_activeUser_anonymizesMessages() {
        stubQueryCalls("ACTIVE");

        service.deleteAccount(userId);

        verify(jdbc).update(contains("UPDATE messages"), any(MapSqlParameterSource.class));
    }

    @Test
    void deleteAccount_activeUser_anonymizesSupportMessages() {
        stubQueryCalls("ACTIVE");

        service.deleteAccount(userId);

        verify(jdbc).update(contains("UPDATE support_messages"), any(MapSqlParameterSource.class));
    }

    @Test
    void deleteAccount_activeUser_insertsAuditLog() {
        stubQueryCalls("ACTIVE");

        service.deleteAccount(userId);

        verify(jdbc).update(contains("INSERT INTO audit_log"), any(MapSqlParameterSource.class));
    }

    @Test
    void deleteAccount_activeUser_deletesStorageObjects() {
        String bucket = "profile-photos";
        String path = "user/photo.jpg";

        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    String sql = (String) inv.getArgument(0);
                    if (sql.contains("SELECT status FROM app_users")) {
                        RowMapper<String> mapper = inv.getArgument(2);
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getString("status")).thenReturn("ACTIVE");
                        return List.of(mapper.mapRow(rs, 0));
                    }
                    if (sql.contains("profile_photos")) {
                        RowMapper<AccountDeletionService.StorageItem> mapper = inv.getArgument(2);
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getString("storage_bucket")).thenReturn(bucket);
                        when(rs.getString("storage_path")).thenReturn(path);
                        return List.of(mapper.mapRow(rs, 0));
                    }
                    return List.of();
                });

        service.deleteAccount(userId);

        verify(storageService).deleteObject(bucket, path);
    }

    @Test
    void deleteAccount_activeUser_evictsCacheAndSoftDeletesAuthUser() {
        stubQueryCalls("ACTIVE");

        service.deleteAccount(userId);

        verify(cache).evict(userId);
        verify(taskRepository).insertPendingIfAbsent(userId);
        verify(authAdminClient).softDeleteAuthUser(userId);
        verify(taskRepository).markCompleted(userId);
    }

    @Test
    void deleteAccount_userNotFound_throws404() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.runTransactionalPhase(userId))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteAccount_alreadyDeleted_skipsDbWritesButStillSoftDeletesAuthUser() {
        stubQueryCalls("DELETED");

        service.deleteAccount(userId);

        verify(jdbc, never()).update(contains("status     = 'DELETED'"), any(MapSqlParameterSource.class));
        verify(subscriptionRevocationService, never()).revokeAll(userId);
        verify(taskRepository).insertPendingIfAbsent(userId);
        verify(authAdminClient).softDeleteAuthUser(userId);
        verify(taskRepository).markCompleted(userId);
        verify(cache).evict(userId);
    }

    @Test
    void deleteAccount_storageFailure_continuesAndAnonymizesAuthUser() {
        String bucket = "profile-photos";
        String path = "user/photo.jpg";
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    String sql = (String) inv.getArgument(0);
                    if (sql.contains("SELECT status FROM app_users")) {
                        RowMapper<String> mapper = inv.getArgument(2);
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getString("status")).thenReturn("ACTIVE");
                        return List.of(mapper.mapRow(rs, 0));
                    }
                    if (sql.contains("profile_photos")) {
                        RowMapper<AccountDeletionService.StorageItem> mapper = inv.getArgument(2);
                        ResultSet rs = mock(ResultSet.class);
                        when(rs.getString("storage_bucket")).thenReturn(bucket);
                        when(rs.getString("storage_path")).thenReturn(path);
                        return List.of(mapper.mapRow(rs, 0));
                    }
                    return List.of();
                });
        doThrow(new RuntimeException("storage error")).when(storageService).deleteObject(any(), any());

        service.deleteAccount(userId);

        verify(authAdminClient).softDeleteAuthUser(userId);
    }

    @Test
    void deleteAccount_authSoftDeleteFailure_doesNotPropagate_taskRemainsPending() {
        stubQueryCalls("ACTIVE");
        doThrow(new SupabaseAuthAdminClient.AuthUserDeletionException("network error"))
                .when(authAdminClient).softDeleteAuthUser(userId);

        assertThatCode(() -> service.deleteAccount(userId)).doesNotThrowAnyException();
        verify(taskRepository).insertPendingIfAbsent(userId);
        verify(taskRepository, never()).markCompleted(userId);
    }
}
