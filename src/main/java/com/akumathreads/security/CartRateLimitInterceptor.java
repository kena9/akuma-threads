package com.akumathreads.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rate-limits POST/DELETE requests to {@code /api/cart/**} at 30 requests
 * per minute per client IP address using a Caffeine in-memory counter.
 *
 * <p>Why here and not a Servlet filter? Spring MVC interceptors run after
 * Spring Security authentication but before controller dispatch — perfect
 * for authenticated-resource throttling where we want to know who the user
 * is (though we key by IP here for simplicity).
 *
 * <p>GET requests (e.g. {@code /api/cart/count}) are exempt — they are
 * read-only, cheap, and used by the navbar badge poll.
 *
 * <p>Registered in {@link com.akumathreads.config.WebConfig} for the path
 * pattern {@code /api/cart/**}.
 */
@Component
public class CartRateLimitInterceptor implements HandlerInterceptor {

    private static final int MAX_REQUESTS_PER_MINUTE = 30;

    /**
     * Per-IP request counter. Entries expire 1 minute after their last write,
     * which effectively gives a sliding window of ~60 seconds.
     *
     * <p>Using AtomicInteger inside a Caffeine entry is safe — Caffeine's
     * get/compute operations are thread-safe and atomic.
     */
    private final Cache<String, AtomicInteger> requestCountCache =
            Caffeine.newBuilder()
                    .expireAfterWrite(1, TimeUnit.MINUTES)
                    .maximumSize(50_000)
                    .build();

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        // Only throttle mutation requests — GET and HEAD are free
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
            return true;
        }

        String clientIp = resolveClientIp(request);
        AtomicInteger counter = requestCountCache.get(clientIp, k -> new AtomicInteger(0));
        int count = counter.incrementAndGet();

        if (count > MAX_REQUESTS_PER_MINUTE) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":\"Too many requests. Please slow down.\",\"retryAfterSeconds\":60}");
            return false;
        }

        return true;
    }

    /**
     * Resolves the real client IP, honouring the {@code X-Forwarded-For} header
     * that AWS Elastic Beanstalk / ALB inserts before the request reaches Tomcat.
     *
     * <p>Only the first (leftmost) entry in {@code X-Forwarded-For} is used — that
     * is the original client IP. Subsequent entries are added by proxies and should
     * not be trusted for rate-limiting.
     *
     * @param request the incoming servlet request
     * @return the client's IP address as a non-null string
     */
    private static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].strip();
        }
        return request.getRemoteAddr();
    }
}
