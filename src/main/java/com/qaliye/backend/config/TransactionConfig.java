package com.qaliye.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;

@Configuration
public class TransactionConfig {

    @Bean
    public org.springframework.beans.factory.config.BeanPostProcessor nestedTransactionEnabler() {
        return new org.springframework.beans.factory.config.BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof AbstractPlatformTransactionManager tpm) {
                    tpm.setNestedTransactionAllowed(true);
                }
                return bean;
            }
        };
    }
}
