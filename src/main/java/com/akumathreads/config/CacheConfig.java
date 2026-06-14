package com.akumathreads.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    /**
     * Stores login failure counts keyed by username or client IP.
     * Entries auto-expire 15 minutes after last write, preventing permanent lockout
     * while still blocking brute-force bursts within a session window.
     */
    @Bean
    public Cache<String, Integer> loginAttemptCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(15, TimeUnit.MINUTES)
                .maximumSize(10_000)
                .build();
    }
}
