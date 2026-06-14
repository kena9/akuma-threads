package com.akumathreads.security;

import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Encapsulates brute-force lockout logic using a Caffeine cache.
 * The cache is keyed by either the attempted username or the client IP address,
 * so a single bad actor is blocked on both axes simultaneously.
 */
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    private static final int MAX_ATTEMPTS = 5;

    private final Cache<String, Integer> loginAttemptCache;

    /**
     * Returns true if the given key (username or IP) has reached the failure threshold.
     * Called BEFORE incrementing so the 5th bad attempt — not the 6th — triggers the lock.
     */
    public boolean isBlocked(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        Integer count = loginAttemptCache.getIfPresent(key);
        return count != null && count >= MAX_ATTEMPTS;
    }

    /**
     * Atomically increments the failure counter for the given key.
     * Uses {@link Cache#asMap()} compute for thread-safe increment.
     */
    public void registerFailure(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        loginAttemptCache.asMap().merge(key, 1, Integer::sum);
    }

    /**
     * Returns the current failure count for the given key, or 0 if none recorded.
     */
    public int getFailureCount(String key) {
        if (key == null || key.isBlank()) {
            return 0;
        }
        Integer count = loginAttemptCache.getIfPresent(key);
        return count == null ? 0 : count;
    }

    /**
     * Returns how many attempts remain before lockout (floor 0).
     */
    public int remainingAttempts(String key) {
        return Math.max(0, MAX_ATTEMPTS - getFailureCount(key));
    }

    /**
     * Clears the failure record for the given key on successful authentication.
     * Called from {@link org.springframework.security.web.authentication.AuthenticationSuccessHandler}.
     */
    public void resetFailures(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        loginAttemptCache.invalidate(key);
    }
}
