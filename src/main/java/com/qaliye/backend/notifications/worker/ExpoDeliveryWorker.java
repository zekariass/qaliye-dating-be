package com.qaliye.backend.notifications.worker;

import com.qaliye.backend.notifications.ExpoPushClient;
import com.qaliye.backend.notifications.ExpoPushClient.TicketResult;
import com.qaliye.backend.notifications.config.PushProperties;
import com.qaliye.backend.notifications.repository.NotificationDeliveryRepository;
import com.qaliye.backend.notifications.repository.NotificationDeliveryRepository.DeliveryRow;
import com.qaliye.backend.notifications.repository.NotificationDeviceJdbcRepository;
import com.qaliye.backend.notifications.repository.NotificationDeviceJdbcRepository.DeviceRow;
import com.qaliye.backend.notifications.repository.NotificationOutboxRepository;
import com.qaliye.backend.notifications.repository.NotificationOutboxRepository.OutboxRow;
import com.qaliye.backend.notifications.service.NotificationEligibilityService;
import com.qaliye.backend.notifications.service.NotificationPayloadBuilder;
import com.qaliye.backend.notifications.service.NotificationPayloadBuilder.ExpoMessage;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class ExpoDeliveryWorker implements Job {

    private static final Logger log = LoggerFactory.getLogger(ExpoDeliveryWorker.class);
    private static final String WORKER_ID_PREFIX = "delivery-";

    @Autowired private NotificationDeliveryRepository deliveryRepo;
    @Autowired private NotificationDeviceJdbcRepository deviceJdbcRepo;
    @Autowired private NotificationOutboxRepository outboxRepo;
    @Autowired private NotificationEligibilityService eligibilityService;
    @Autowired private NotificationPayloadBuilder payloadBuilder;
    @Autowired private ExpoPushClient expoPushClient;
    @Autowired private PushProperties pushProperties;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        String workerId = WORKER_ID_PREFIX + context.getFireInstanceId();
        PushProperties.Delivery cfg = pushProperties.getDelivery();
        int batchSize = pushProperties.getExpo().getSendBatchSize();

        try {
            List<DeliveryRow> rows = deliveryRepo.claimPending(
                    workerId, batchSize, cfg.getLeaseSeconds());
            if (!rows.isEmpty()) {
                processDeliveryBatch(rows, cfg);
            }

            List<DeliveryRow> expired = deliveryRepo.reclaimExpired(
                    workerId, batchSize, cfg.getLeaseSeconds());
            if (!expired.isEmpty()) {
                processDeliveryBatch(expired, cfg);
            }
        } catch (Exception e) {
            log.error("ExpoDeliveryWorker failed: {}", e.getMessage());
            throw new JobExecutionException(e, false);
        }
    }

    private void processDeliveryBatch(List<DeliveryRow> rows, PushProperties.Delivery cfg) {
        List<ExpoMessage> messages = new ArrayList<>(rows.size());
        List<DeliveryRow> eligible = new ArrayList<>(rows.size());

        for (DeliveryRow delivery : rows) {
            try {
                Optional<OutboxRow> outboxOpt = outboxRepo.findById(delivery.notificationOutboxEventId());
                if (outboxOpt.isEmpty()) {
                    deliveryRepo.markSkipped(delivery.id(), "OUTBOX_NOT_FOUND");
                    continue;
                }
                OutboxRow outbox = outboxOpt.get();

                Optional<DeviceRow> deviceOpt = deviceJdbcRepo.findById(delivery.notificationDeviceId());
                if (deviceOpt.isEmpty()) {
                    deliveryRepo.markSkipped(delivery.id(), "DEVICE_NOT_FOUND");
                    continue;
                }
                DeviceRow device = deviceOpt.get();

                NotificationEligibilityService.EligibilityResult check =
                        eligibilityService.checkDeliveryEligibility(
                                device.userId(),
                                outbox.recipientUserId(),
                                device.isActive(),
                                device.appEnvironment(),
                                pushProperties.getAppEnvironment(),
                                outbox);

                if (!check.eligible()) {
                    deliveryRepo.markSkipped(delivery.id(), check.skipReason());
                    continue;
                }

                ExpoMessage msg = payloadBuilder.buildForDelivery(outbox, device.deviceToken());
                messages.add(msg);
                eligible.add(delivery);

            } catch (Exception e) {
                log.error("Pre-submission check failed for delivery {}: {}", delivery.id(), e.getMessage());
                long delay = BackoffCalculator.compute(delivery.attemptCount(), cfg.getMaxBackoffSeconds());
                if (delivery.attemptCount() >= cfg.getMaxAttempts()) {
                    deliveryRepo.markFailed(delivery.id(), "PRE_CHECK_ERROR", e.getMessage());
                } else {
                    deliveryRepo.requeue(delivery.id(), delay, "PRE_CHECK_ERROR", e.getMessage());
                }
            }
        }

        if (messages.isEmpty()) return;

        List<TicketResult> tickets;
        try {
            tickets = expoPushClient.sendBatch(messages);
        } catch (ExpoPushClient.ExpoProviderException e) {
            log.error("Expo sendBatch threw provider exception: {}", e.getMessage());
            for (DeliveryRow d : eligible) {
                long delay = BackoffCalculator.compute(d.attemptCount(), cfg.getMaxBackoffSeconds());
                if (d.attemptCount() >= cfg.getMaxAttempts()) {
                    deliveryRepo.markFailed(d.id(), "PROVIDER_ERROR", e.getMessage());
                } else {
                    deliveryRepo.requeue(d.id(), delay, "PROVIDER_ERROR", e.getMessage());
                }
            }
            return;
        }

        OffsetDateTime receiptCheckAt = OffsetDateTime.now()
                .plusMinutes(pushProperties.getReceipts().getInitialDelayMinutes());
        OffsetDateTime receiptDeadlineAt = OffsetDateTime.now()
                .plusHours(pushProperties.getReceipts().getDeadlineHours());

        for (int i = 0; i < eligible.size() && i < tickets.size(); i++) {
            DeliveryRow delivery = eligible.get(i);
            TicketResult ticket = tickets.get(i);

            if (ticket.isOk()) {
                deliveryRepo.markSubmitted(delivery.id(), ticket.ticketId(),
                        receiptCheckAt, receiptDeadlineAt);
            } else if (ticket.isDeviceNotRegistered()) {
                String token = messages.get(i).to();
                deviceJdbcRepo.markDeviceNotRegistered(token, "DeviceNotRegistered");
                deliveryRepo.markFailed(delivery.id(), "DeviceNotRegistered", ticket.errorMessage());
            } else if (ticket.isRetryable()) {
                long delay = BackoffCalculator.compute(delivery.attemptCount(), cfg.getMaxBackoffSeconds());
                if (delivery.attemptCount() >= cfg.getMaxAttempts()) {
                    deliveryRepo.markFailed(delivery.id(), ticket.errorCode(), ticket.errorMessage());
                } else {
                    deliveryRepo.requeue(delivery.id(), delay, ticket.errorCode(), ticket.errorMessage());
                }
            } else {
                deliveryRepo.markFailed(delivery.id(), ticket.errorCode(), ticket.errorMessage());
            }
        }
    }

}
