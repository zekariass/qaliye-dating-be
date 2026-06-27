package com.qaliye.backend.activity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ActivityConfig {

    @Bean
    Clock utcClock() {
        return Clock.systemUTC();
    }
}
