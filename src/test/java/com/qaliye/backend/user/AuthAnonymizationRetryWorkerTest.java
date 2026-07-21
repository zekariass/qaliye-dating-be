package com.qaliye.backend.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthAnonymizationRetryWorkerTest {

    @Mock AuthAnonymizationTaskRepository taskRepository;
    @Mock SupabaseAuthAdminClient authAdminClient;

    AuthAnonymizationRetryWorker worker;
    UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        worker = new AuthAnonymizationRetryWorker(taskRepository, authAdminClient);
    }

    @Test
    void retryPendingTasks_noPending_doesNotCallSoftDelete() {
        when(taskRepository.claimPending(anyInt())).thenReturn(List.of());

        worker.retryPendingTasks();

        verify(authAdminClient, never()).softDeleteAuthUser(any());
        verify(taskRepository, never()).markCompleted(any());
    }

    @Test
    void retryPendingTasks_softDeleteSucceeds_marksCompleted() {
        when(taskRepository.claimPending(anyInt())).thenReturn(List.of(userId));

        worker.retryPendingTasks();

        verify(authAdminClient).softDeleteAuthUser(userId);
        verify(taskRepository).markCompleted(userId);
        verify(taskRepository, never()).scheduleRetry(any(), anyString());
    }

    @Test
    void retryPendingTasks_softDeleteFails_schedulesRetryNotCompleted() {
        when(taskRepository.claimPending(anyInt())).thenReturn(List.of(userId));
        doThrow(new SupabaseAuthAdminClient.AuthUserDeletionException("network error"))
                .when(authAdminClient).softDeleteAuthUser(userId);

        worker.retryPendingTasks();

        verify(taskRepository, never()).markCompleted(userId);
        verify(taskRepository).scheduleRetry(userId, "network error");
    }

    @Test
    void retryPendingTasks_processesMultipleTasks() {
        UUID userId2 = UUID.randomUUID();
        when(taskRepository.claimPending(anyInt())).thenReturn(List.of(userId, userId2));

        worker.retryPendingTasks();

        verify(authAdminClient).softDeleteAuthUser(userId);
        verify(authAdminClient).softDeleteAuthUser(userId2);
        verify(taskRepository).markCompleted(userId);
        verify(taskRepository).markCompleted(userId2);
    }

    @Test
    void retryPendingTasks_oneFailsOtherSucceeds_handlesIndependently() {
        UUID failId = UUID.randomUUID();
        UUID successId = UUID.randomUUID();
        when(taskRepository.claimPending(anyInt())).thenReturn(List.of(failId, successId));
        doThrow(new SupabaseAuthAdminClient.AuthUserDeletionException("timeout"))
                .when(authAdminClient).softDeleteAuthUser(failId);

        worker.retryPendingTasks();

        verify(taskRepository).scheduleRetry(failId, "timeout");
        verify(taskRepository).markCompleted(successId);
        verify(taskRepository, never()).scheduleRetry(eq(successId), anyString());
        verify(taskRepository, never()).markCompleted(failId);
    }

    @Test
    void retryPendingTasks_reachesMaxAttempts_scheduleRetryCalledWhichMarksFailedPermanent() {
        when(taskRepository.claimPending(anyInt())).thenReturn(List.of(userId));
        doThrow(new SupabaseAuthAdminClient.AuthUserDeletionException("persistent error"))
                .when(authAdminClient).softDeleteAuthUser(userId);

        worker.retryPendingTasks();

        verify(taskRepository).scheduleRetry(userId, "persistent error");
    }
}
