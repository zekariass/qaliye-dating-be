package com.qaliye.backend.payments;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SubscriptionReconciliationJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionReconciliationJob.class);

    @Autowired
    private PaymentService paymentService;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        log.info("SubscriptionReconciliationJob starting");
        try {
            paymentService.reconcileSubscriptions();
        } catch (Exception e) {
            log.error("SubscriptionReconciliationJob failed: {}", e.getMessage());
            throw new JobExecutionException(e, false);
        }
    }
}
