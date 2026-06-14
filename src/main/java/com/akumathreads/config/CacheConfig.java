package com.akumathreads.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Application-wide caching configuration.
 *
 * <p>{@code @EnableCaching} activates Spring's annotation-driven cache infrastructure,
 * which processes {@code @Cacheable}, {@code @CacheEvict}, and {@code @CachePut}
 * annotations on beans managed by the Spring container.
 *
 * <p>Two caches are declared:
 * <ul>
 *   <li>{@code "products"} — stores the result of
 *       {@link com.akumathreads.service.ProductService#findFiltered(String, com.akumathreads.model.Product.Category, java.math.BigDecimal, java.math.BigDecimal, org.springframework.data.domain.Pageable)}
 *       so that repeated shop-page loads with the same filters hit the cache
 *       instead of the database. Evicted on any product write.</li>
 *   <li>{@code "loginAttempts"} — backed by a separate Caffeine cache bean below
 *       (the Caffeine cache is used directly via injection, not via Spring Cache abstraction).</li>
 * </ul>
 *
 * <p>For the capstone scale, {@link ConcurrentMapCacheManager} is sufficient.
 * In a future production upgrade, swap for a Caffeine-backed {@code CaffeineCacheManager}
 * with TTL or a distributed Redis {@code RedisCacheManager}.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * Spring Cache abstraction manager — powers {@code @Cacheable} and
     * {@code @CacheEvict} on {@link com.akumathreads.service.ProductService}.
     *
     * <p>{@link ConcurrentMapCacheManager} uses a plain {@code ConcurrentHashMap}
     * internally — no external dependencies, zero configuration. Cache entries
     * live until evicted by a write operation or until the JVM restarts.
     */
    @Bean
    public CacheManager cacheManager() {
        return new ConcurrentMapCacheManager("products");
    }

    /**
     * Caffeine-backed login-attempt counter used directly by
     * {@link com.akumathreads.security.LoginAttemptService}.
     *
     * <p>This cache is <em>not</em> exposed through the Spring Cache abstraction —
     * it is injected as a raw {@code Cache<String, Integer>} bean so that
     * {@code LoginAttemptService} can call Caffeine-specific APIs
     * (e.g. {@code get(key, mappingFunction)}).
     *
     * <p>Entries auto-expire 15 minutes after last write, preventing permanent
     * account lockout while still blocking brute-force bursts within a window.
     */
    @Bean
    public Cache<String, Integer> loginAttemptCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(15, TimeUnit.MINUTES)
                .maximumSize(10_000)
                .build();
    }
}
