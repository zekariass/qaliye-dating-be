package com.qaliye.backend.auth.sms;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(SmsProperties.class)
public class SmsConfig {

    @Bean
    public SmsProvider smsProvider(SmsProperties props, RestClient.Builder builder) {
        String name = props.getProvider();
        if (name == null || name.isBlank()) {
            throw new IllegalStateException(
                    "sms.provider must be configured. Valid values: AFROMESSAGE, SMS_ETHIOPIA");
        }
        return switch (name.toUpperCase()) {
            case "AFROMESSAGE" -> new AfroMessageSmsProvider(props.getAfromessage(), builder.build());
            case "SMS_ETHIOPIA" -> new SmsEthiopiaSmsProvider(props.getSmsEthiopia(), builder.build());
            default -> throw new IllegalStateException(
                    "Unknown sms.provider: '" + name + "'. Valid values: AFROMESSAGE, SMS_ETHIOPIA");
        };
    }
}
