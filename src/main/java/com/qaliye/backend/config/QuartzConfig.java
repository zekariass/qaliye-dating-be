package com.qaliye.backend.config;

import com.qaliye.backend.moderation.MessageModerationJob;
import com.qaliye.backend.notifications.worker.ExpoDeliveryWorker;
import com.qaliye.backend.notifications.worker.ExpoReceiptWorker;
import com.qaliye.backend.notifications.worker.NotificationOutboxFanoutWorker;
import com.qaliye.backend.notifications.worker.NotificationRecoveryWorker;
import com.qaliye.backend.payments.SubscriptionReconciliationJob;
import com.qaliye.backend.user.DataDeletionJob;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.boot.quartz.autoconfigure.SchedulerFactoryBeanCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;
import java.util.TimeZone;

@Configuration
public class QuartzConfig {

    @Bean
    public SchedulerFactoryBeanCustomizer quartzRamJobStore() {
        return schedulerFactoryBean -> {
            Properties props = new Properties();
            props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
            schedulerFactoryBean.setQuartzProperties(props);
        };
    }

    @Bean
    public JobDetail messageModerationJobDetail() {
        return JobBuilder.newJob(MessageModerationJob.class)
                .withIdentity("messageModerationJob")
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger messageModerationTrigger(JobDetail messageModerationJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(messageModerationJobDetail)
                .withIdentity("messageModerationTrigger")
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInMinutes(15)
                        .repeatForever())
                .build();
    }

    @Bean
    public JobDetail subscriptionReconciliationJobDetail() {
        return JobBuilder.newJob(SubscriptionReconciliationJob.class)
                .withIdentity("subscriptionReconciliationJob")
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger subscriptionReconciliationTrigger(JobDetail subscriptionReconciliationJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(subscriptionReconciliationJobDetail)
                .withIdentity("subscriptionReconciliationTrigger")
                .withSchedule(CronScheduleBuilder.cronSchedule("0 0 2 * * ?")
                        .inTimeZone(TimeZone.getTimeZone("UTC")))
                .build();
    }

    @Bean
    public JobDetail dataDeletionJobDetail() {
        return JobBuilder.newJob(DataDeletionJob.class)
                .withIdentity("dataDeletionJob")
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger dataDeletionTrigger(JobDetail dataDeletionJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(dataDeletionJobDetail)
                .withIdentity("dataDeletionTrigger")
                .withSchedule(CronScheduleBuilder.cronSchedule("0 0 3 ? * SUN")
                        .inTimeZone(TimeZone.getTimeZone("UTC")))
                .build();
    }

    @Bean
    public JobDetail notificationOutboxFanoutJobDetail() {
        return JobBuilder.newJob(NotificationOutboxFanoutWorker.class)
                .withIdentity("notificationOutboxFanoutJob")
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger notificationOutboxFanoutTrigger(JobDetail notificationOutboxFanoutJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(notificationOutboxFanoutJobDetail)
                .withIdentity("notificationOutboxFanoutTrigger")
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInSeconds(1)
                        .repeatForever())
                .build();
    }

    @Bean
    public JobDetail expoDeliveryJobDetail() {
        return JobBuilder.newJob(ExpoDeliveryWorker.class)
                .withIdentity("expoDeliveryJob")
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger expoDeliveryTrigger(JobDetail expoDeliveryJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(expoDeliveryJobDetail)
                .withIdentity("expoDeliveryTrigger")
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInSeconds(1)
                        .repeatForever())
                .build();
    }

    @Bean
    public JobDetail expoReceiptJobDetail() {
        return JobBuilder.newJob(ExpoReceiptWorker.class)
                .withIdentity("expoReceiptJob")
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger expoReceiptTrigger(JobDetail expoReceiptJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(expoReceiptJobDetail)
                .withIdentity("expoReceiptTrigger")
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInSeconds(30)
                        .repeatForever())
                .build();
    }

    @Bean
    public JobDetail notificationRecoveryJobDetail() {
        return JobBuilder.newJob(NotificationRecoveryWorker.class)
                .withIdentity("notificationRecoveryJob")
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger notificationRecoveryTrigger(JobDetail notificationRecoveryJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(notificationRecoveryJobDetail)
                .withIdentity("notificationRecoveryTrigger")
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInMinutes(2)
                        .repeatForever())
                .build();
    }
}
