package com.qaliye.backend.notifications.worker;

import com.qaliye.backend.notifications.config.PushProperties;
import com.qaliye.backend.notifications.repository.NotificationDeliveryRepository;
import com.qaliye.backend.notifications.repository.NotificationDeviceJdbcRepository;
import com.qaliye.backend.notifications.repository.NotificationDeviceJdbcRepository.DeviceRow;
import com.qaliye.backend.notifications.repository.NotificationOutboxRepository;
import com.qaliye.backend.notifications.repository.NotificationOutboxRepository.OutboxRow;
import com.qaliye.backend.notifications.service.NotificationEligibilityService;
import com.qaliye.backend.notifications.service.NotificationEligibilityService.EligibilityResult;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

@Component
public class NotificationOutboxFanoutWorker implements Job {

    private static final Logger log = LoggerFactory.getLogger(NotificationOutboxFanoutWorker.class);
    private static final String WORKER_ID_PREFIX = "fanout-";

    @Autowired private NotificationOutboxRepository outboxRepo;
    @Autowired private NotificationDeliveryRepository deliveryRepo;
    @Autowired private NotificationDeviceJdbcRepository deviceJdbcRepo;
    @Autowired private NotificationEligibilityService eligibilityService;
    @Autowired private PushProperties pushProperties;
    @Autowired private TransactionTemplate txTemplate;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        String workerId = WORKER_ID_PREFIX + context.getFireInstanceId();
        PushProperties.Outbox cfg = pushProperties.getOutbox();

        try {
            List<OutboxRow> rows = outboxRepo.claimPending(
                    workerId, cfg.getBatchSize(), cfg.getLeaseSeconds());
            for (OutboxRow row : rows) {
                processRow(row, workerId, cfg);
            }

            List<OutboxRow> expired = outboxRepo.reclaimExpired(
                    workerId, cfg.getBatchSize(), cfg.getLeaseSeconds());
            for (OutboxRow row : expired) {
                processRow(row, workerId, cfg);
            }
        } catch (Exception e) {
            log.error("NotificationOutboxFanoutWorker failed: {}", e.getMessage());
            throw new JobExecutionException(e, false);
        }
    }

    private void processRow(OutboxRow row, String workerId, PushProperties.Outbox cfg) {
        try {
            txTemplate.execute(status -> {
                doFanout(row, cfg);
                return null;
            });
        } catch (Exception e) {
            log.error("Fanout failed for outbox event {}: {}", row.id(), e.getMessage());
            long delay = BackoffCalculator.compute(row.attemptCount(), cfg.getMaxBackoffSeconds());
            if (row.attemptCount() >= cfg.getMaxAttempts()) {
                outboxRepo.markFailed(row.id(), e.getMessage());
            } else {
                outboxRepo.requeue(row.id(), delay, e.getMessage());
            }
        }
    }

    private void doFanout(OutboxRow row, PushProperties.Outbox cfg) {
        EligibilityResult eligibility = eligibilityService.checkOutboxEligibility(row);
        if (!eligibility.eligible()) {
            outboxRepo.markSkipped(row.id(), eligibility.skipReason());
            return;
        }

        List<DeviceRow> devices = deviceJdbcRepo.findActiveDevicesForUser(
                row.recipientUserId(), pushProperties.getAppEnvironment());

        if (devices.isEmpty()) {
            outboxRepo.markSkipped(row.id(), "NO_ACTIVE_DEVICE");
            return;
        }

        for (DeviceRow device : devices) {
            UUID deliveryId = UUID.randomUUID();
            deliveryRepo.insert(deliveryId, row.id(), device.id());
        }

        outboxRepo.markFanoutComplete(row.id());
    }
}
