package com.qaliye.backend.matchmaking.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MatchmakingProperties.class)
public class MatchmakingConfig {
}
