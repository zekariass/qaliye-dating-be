package com.qaliye.backend.config;

import com.qaliye.backend.moderation.MessageModerationJob;
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
}
