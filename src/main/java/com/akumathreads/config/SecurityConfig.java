package com.akumathreads.config;

import com.akumathreads.security.CustomAuthenticationFailureHandler;
import com.akumathreads.security.CustomAuthenticationSuccessHandler;
import org.apache.catalina.Context;
import org.apache.tomcat.util.http.Rfc6265CookieProcessor;
import org.apache.tomcat.util.http.SameSiteCookies;
import org.springframework.boot.web.embedded.tomcat.TomcatContextCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.session.HttpSessionEventPublisher;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final String CSP =
            "default-src 'self'; " +
            // Tailwind CDN is our only permitted external script source.
            // In production, replace with a compiled Tailwind build and remove this entry.
            "script-src 'self' 'unsafe-inline' https://cdn.tailwindcss.com; " +
            // 'unsafe-inline' required for Tailwind's runtime JIT style injection.
            "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; " +
            "img-src 'self' data: https:; " +
            "font-src 'self' https://fonts.gstatic.com; " +
            "frame-ancestors 'none'; " +
            "form-action 'self'";

    private final CustomAuthenticationFailureHandler failureHandler;
    private final CustomAuthenticationSuccessHandler successHandler;

    public SecurityConfig(CustomAuthenticationFailureHandler failureHandler,
                          CustomAuthenticationSuccessHandler successHandler) {
        this.failureHandler = failureHandler;
        this.successHandler = successHandler;
    }

    // ── Security Filter Chain ────────────────────────────────────────────────

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // ── CSRF — cookie-backed so JS can always read a fresh token ────────
            // CookieCsrfTokenRepository writes XSRF-TOKEN (httpOnly=false so JS can read it).
            // CsrfTokenRequestAttributeHandler resolves the token from the cookie on every request.
            // JS reads it via getCsrfToken() in cart.js and sends it as X-XSRF-TOKEN header.
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
            )

            // ── Authorization rules ──────────────────────────────────────────
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/", "/shop/**", "/product/**",
                    "/register", "/login",
                    "/privacy-policy", "/terms-of-service", "/refund-and-shipping",
                    "/css/**", "/js/**", "/img/**", "/images/**", "/error"
                ).permitAll()
                // Cart API — guests may add items; login is required only at checkout
                .requestMatchers("/api/cart/**").permitAll()
                // Cart page is auth-only (consistent: navbar Cart link is auth-only too)
                .requestMatchers("/cart", "/account/**", "/checkout/**", "/orders/**", "/order/**").authenticated()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )

            // ── Form login ───────────────────────────────────────────────────
            .formLogin(form -> form
                .loginPage("/login")
                .usernameParameter("username")
                .passwordParameter("password")
                .successHandler(successHandler)
                .failureHandler(failureHandler)
                .permitAll()
            )

            // ── Logout ───────────────────────────────────────────────────────
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout=true")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
                .permitAll()
            )

            // ── Session management ───────────────────────────────────────────
            .sessionManagement(session -> session
                // changeSessionId prevents session fixation attacks while preserving
                // session attributes (preferred over newSession which loses cart state).
                .sessionFixation(fixation -> fixation.changeSessionId())
                .sessionConcurrency(concurrency -> concurrency
                    .maximumSessions(1)
                    .expiredUrl("/login?sessionExpired=true")
                )
            )

            // ── Security headers ─────────────────────────────────────────────
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                .httpStrictTransportSecurity(hsts -> hsts
                    // 1 year, include subdomains, eligible for browser preload list.
                    // Only effective over HTTPS — Elastic Beanstalk terminates TLS at ALB.
                    .maxAgeInSeconds(31_536_000)
                    .includeSubDomains(true)
                    .preload(true)
                )
                .frameOptions(frame -> frame.deny())
                .contentTypeOptions(Customizer.withDefaults())
                .referrerPolicy(referrer -> referrer
                    .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)
                )
            );

        return http.build();
    }

    // ── Beans ────────────────────────────────────────────────────────────────

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /**
     * Required for Spring Security concurrent session control.
     * Without this publisher, the session registry never learns about session
     * creation/destruction events and cannot enforce maximumSessions(1).
     */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    /**
     * Sets SameSite=Strict on the JSESSIONID cookie at the Tomcat level.
     *
     * <p>Note: also set {@code server.servlet.session.cookie.secure=true} in
     * {@code application-prod.properties} so the Secure flag is only active
     * in production (HTTPS), preventing the dev server from breaking on HTTP.
     */
    @Bean
    public TomcatContextCustomizer sameSiteCookieCustomizer() {
        return (Context context) -> {
            Rfc6265CookieProcessor cookieProcessor = new Rfc6265CookieProcessor();
            cookieProcessor.setSameSiteCookies(SameSiteCookies.STRICT.getValue());
            context.setCookieProcessor(cookieProcessor);
        };
    }
}
