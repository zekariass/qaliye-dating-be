package com.qaliye.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.support.CompositeCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfig {

    @Value("${app.cache.user-status-ttl-seconds}")
    private long userStatusTtlSeconds;

    @Value("${app.cache.subscription-features-ttl-seconds}")
    private long subscriptionFeaturesTtlSeconds;

    @Bean
    public CacheManager cacheManager() {
        CompositeCacheManager composite = new CompositeCacheManager(
                buildCaffeineCacheManager("userStatus", userStatusTtlSeconds),
                buildCaffeineCacheManager("subscriptionFeatures", subscriptionFeaturesTtlSeconds)
        );
        composite.setFallbackToNoOpCache(false);
        return composite;
    }

    private CaffeineCacheManager buildCaffeineCacheManager(String cacheName, long ttlSeconds) {
        CaffeineCacheManager manager = new CaffeineCacheManager(cacheName);
        manager.setCacheSpecification("maximumSize=10000,expireAfterWrite=" + ttlSeconds + "s");
        return manager;
    }
}
