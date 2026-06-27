package com.qaliye.backend.notifications.worker;

import com.qaliye.backend.notifications.ExpoPushClient;
import com.qaliye.backend.notifications.ExpoPushClient.ReceiptResult;
import com.qaliye.backend.notifications.config.PushProperties;
import com.qaliye.backend.notifications.repository.NotificationDeliveryRepository;
import com.qaliye.backend.notifications.repository.NotificationDeliveryRepository.DeliveryRow;
import com.qaliye.backend.notifications.repository.NotificationDeviceJdbcRepository;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Component
public class ExpoReceiptWorker implements Job {

    private static final Logger log = LoggerFactory.getLogger(ExpoReceiptWorker.class);
    private static final String WORKER_ID_PREFIX = "receipt-";

    @Autowired private NotificationDeliveryRepository deliveryRepo;
    @Autowired private NotificationDeviceJdbcRepository deviceJdbcRepo;
    @Autowired private ExpoPushClient expoPushClient;
    @Autowired private PushProperties pushProperties;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        String workerId = WORKER_ID_PREFIX + context.getFireInstanceId();
        int batchSize = pushProperties.getExpo().getReceiptBatchSize();
        int leaseSeconds = pushProperties.getDelivery().getLeaseSeconds();

        try {
            List<DeliveryRow> rows = deliveryRepo.claimForReceiptCheck(
                    workerId, batchSize, leaseSeconds);
            if (rows.isEmpty()) return;

            List<String> ticketIds = rows.stream()
                    .map(DeliveryRow::providerTicketId)
                    .filter(id -> id != null && !id.isBlank())
                    .toList();

            if (ticketIds.isEmpty()) {
                rows.forEach(r -> deliveryRepo.markUnknown(r.id()));
                return;
            }

            Map<String, ReceiptResult> receipts;
            try {
                receipts = expoPushClient.fetchReceipts(ticketIds);
            } catch (ExpoPushClient.ExpoProviderException e) {
                log.error("ExpoReceiptWorker fetchReceipts failed: {}", e.getMessage());
                rows.forEach(r -> deliveryRepo.markUnknown(r.id()));
                return;
            }

            for (DeliveryRow delivery : rows) {
                processReceipt(delivery, receipts);
            }

        } catch (Exception e) {
            log.error("ExpoReceiptWorker failed: {}", e.getMessage());
            throw new JobExecutionException(e, false);
        }
    }

    private void processReceipt(DeliveryRow delivery, Map<String, ReceiptResult> receipts) {
        String ticketId = delivery.providerTicketId();
        ReceiptResult receipt = ticketId != null ? receipts.get(ticketId) : null;

        if (receipt == null) {
            deliveryRepo.markUnknown(delivery.id());
            return;
        }

        if (receipt.isOk()) {
            deliveryRepo.markConfirmed(delivery.id());
        } else if (receipt.isDeviceNotRegistered()) {
            deviceJdbcRepo.markDeviceNotRegisteredById(
                    delivery.notificationDeviceId(), "DeviceNotRegistered");
            deliveryRepo.markFailed(delivery.id(), "DeviceNotRegistered", receipt.errorMessage());
        } else {
            deliveryRepo.markFailed(delivery.id(), receipt.errorCode(), receipt.errorMessage());
        }
    }

    private boolean isPastDeadline(DeliveryRow delivery) {
        return delivery.receiptDeadlineAt() != null
                && delivery.receiptDeadlineAt().isBefore(OffsetDateTime.now());
    }
}
