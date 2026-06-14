package com.akumathreads.config;

import com.akumathreads.security.CartRateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC configuration.
 *
 * <p>Registers the {@link CartRateLimitInterceptor} on {@code /api/cart/**}
 * so that cart mutation endpoints (POST add, DELETE remove) are throttled at
 * 30 requests per minute per client IP. The cart count endpoint
 * ({@code GET /api/cart/count}) is exempt — the interceptor skips GET requests.
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final CartRateLimitInterceptor cartRateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(cartRateLimitInterceptor)
                .addPathPatterns("/api/cart/**");
    }
}
