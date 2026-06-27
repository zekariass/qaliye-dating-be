package com.qaliye.backend.chat.outbox;

import com.qaliye.backend.chat.config.ChatProperties;
import com.qaliye.backend.chat.repository.ChatOutboxRepository;
import com.qaliye.backend.chat.repository.ChatOutboxRepository.OutboxRow;
import com.qaliye.backend.chat.service.ChatRealtimePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class ChatOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(ChatOutboxPublisher.class);

    private final ChatOutboxRepository outboxRepo;
    private final ChatRealtimePublisher realtimePublisher;
    private final ChatProperties props;
    private final TransactionTemplate txTemplate;
    private final String workerId;

    public ChatOutboxPublisher(ChatOutboxRepository outboxRepo,
                                ChatRealtimePublisher realtimePublisher,
                                ChatProperties props,
                                PlatformTransactionManager txManager) {
        this.outboxRepo = outboxRepo;
        this.realtimePublisher = realtimePublisher;
        this.props = props;
        this.txTemplate = new TransactionTemplate(txManager);
        this.workerId = resolveWorkerId();
    }

    @Scheduled(fixedDelayString = "${chat.outbox.poll-interval-ms:500}")
    public void processOutbox() {
        try {
            reclaimExpiredLeases();
            claimAndPublish();
        } catch (Exception e) {
            log.error("Outbox processing error: {}", e.getMessage(), e);
        }
    }

    private void claimAndPublish() {
        int batchSize   = props.getOutbox().getBatchSize();
        int leaseSeconds = props.getOutbox().getLeaseSeconds();
        int maxAttempts = props.getOutbox().getMaxAttempts();
        int maxBackoff  = props.getOutbox().getMaxBackoffSeconds();

        List<OutboxRow> claimed = txTemplate.execute(status ->
                outboxRepo.claimPending(workerId, batchSize, leaseSeconds));

        if (claimed == null || claimed.isEmpty()) return;

        log.debug("Claimed {} outbox events", claimed.size());

        for (OutboxRow row : claimed) {
            publishOne(row, maxAttempts, maxBackoff);
        }
    }

    private void reclaimExpiredLeases() {
        int batchSize    = props.getOutbox().getBatchSize();
        int leaseSeconds = props.getOutbox().getLeaseSeconds();
        int maxAttempts  = props.getOutbox().getMaxAttempts();
        int maxBackoff   = props.getOutbox().getMaxBackoffSeconds();

        List<OutboxRow> reclaimed = txTemplate.execute(status ->
                outboxRepo.reclaimExpired(workerId, batchSize, leaseSeconds));

        if (reclaimed == null || reclaimed.isEmpty()) return;

        log.debug("Reclaimed {} expired outbox leases", reclaimed.size());

        for (OutboxRow row : reclaimed) {
            publishOne(row, maxAttempts, maxBackoff);
        }
    }

    private void publishOne(OutboxRow row, int maxAttempts, int maxBackoffSeconds) {
        try {
            realtimePublisher.publishBroadcast(row.topic(), row.eventType(), row.payloadJson());
            txTemplate.execute(status -> {
                outboxRepo.markPublished(row.id());
                return null;
            });
            log.debug("Published outbox event id={} type={}", row.id(), row.eventType());
        } catch (Exception e) {
            log.warn("Failed to publish outbox event id={} attempt={}: {}",
                    row.id(), row.attemptCount(), e.getMessage());
            handleFailure(row, e, maxAttempts, maxBackoffSeconds);
        }
    }

    private void handleFailure(OutboxRow row, Exception e, int maxAttempts, int maxBackoffSeconds) {
        String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        int attempt = row.attemptCount();

        if (attempt >= maxAttempts) {
            txTemplate.execute(status -> {
                outboxRepo.markFailed(row.id(), errMsg);
                return null;
            });
            log.error("Outbox event id={} permanently failed after {} attempts", row.id(), attempt);
            return;
        }

        long delaySeconds = computeBackoff(attempt, maxBackoffSeconds);
        txTemplate.execute(status -> {
            outboxRepo.requeue(row.id(), delaySeconds, errMsg);
            return null;
        });
        log.debug("Requeued outbox event id={} with delay={}s", row.id(), delaySeconds);
    }

    private long computeBackoff(int attempt, int maxBackoffSeconds) {
        long base = Math.min((long) Math.pow(2, attempt), maxBackoffSeconds);
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, base / 2));
        return Math.min(base + jitter, maxBackoffSeconds);
    }

    private String resolveWorkerId() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + ProcessHandle.current().pid();
        } catch (Exception e) {
            return "worker-" + ThreadLocalRandom.current().nextLong();
        }
    }
}
