package com.qaliye.backend.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
class AuthAnonymizationRetryWorker {

    private static final Logger log = LoggerFactory.getLogger(AuthAnonymizationRetryWorker.class);
    private static final int BATCH_SIZE = 20;

    private final AuthAnonymizationTaskRepository taskRepository;
    private final SupabaseAuthAdminClient authAdminClient;

    AuthAnonymizationRetryWorker(AuthAnonymizationTaskRepository taskRepository,
                                  SupabaseAuthAdminClient authAdminClient) {
        this.taskRepository = taskRepository;
        this.authAdminClient = authAdminClient;
    }

    @Scheduled(fixedDelay = 300_000)
    void retryPendingTasks() {
        List<UUID> pending = taskRepository.claimPending(BATCH_SIZE);
        if (pending.isEmpty()) {
            return;
        }
        log.info("Retrying {} pending auth anonymization task(s)", pending.size());
        for (UUID userId : pending) {
            try {
                authAdminClient.softDeleteAuthUser(userId);
                taskRepository.markCompleted(userId);
                log.info("Auth soft-delete retry succeeded for user {}", userId);
            } catch (Exception e) {
                log.warn("Auth soft-delete retry failed for user {}: {}", userId, e.getMessage());
                taskRepository.scheduleRetry(userId, e.getMessage());
            }
        }
    }
}
