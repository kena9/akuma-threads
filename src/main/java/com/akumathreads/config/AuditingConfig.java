package com.akumathreads.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * Enables Spring Data JPA auditing and registers the {@link AuditorAware} implementation
 * that populates {@code createdBy} and {@code lastModifiedBy} on all entities that
 * extend {@link com.akumathreads.entity.BaseAuditEntity}.
 *
 * <p>The auditor resolution rules:
 * <ol>
 *   <li>Authenticated user → returns {@code authentication.getName()} (the email address,
 *       per {@link com.akumathreads.service.UserDetailsServiceImpl}).</li>
 *   <li>Anonymous or null authentication → returns {@code "SYSTEM"}, used for
 *       background jobs, data migrations, and application startup seeders.</li>
 * </ol>
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorProvider")
public class AuditingConfig {

    /**
     * Bean name must match the {@code auditorAwareRef} value above.
     */
    @Bean
    public AuditorAware<String> auditorProvider() {
        return () -> {
            Authentication authentication =
                    SecurityContextHolder.getContext().getAuthentication();

            if (authentication == null
                    || !authentication.isAuthenticated()
                    || authentication instanceof AnonymousAuthenticationToken) {
                return Optional.of("SYSTEM");
            }

            return Optional.of(authentication.getName());
        };
    }
}
