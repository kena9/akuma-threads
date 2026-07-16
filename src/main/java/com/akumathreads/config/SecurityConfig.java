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
            // script-src: Tailwind CDN (replace with compiled build before launch) + Stripe.js
            "script-src 'self' 'unsafe-inline' https://cdn.tailwindcss.com https://js.stripe.com; " +
            // 'unsafe-inline' required for Tailwind runtime JIT; remove once Tailwind is self-hosted.
            "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; " +
            "img-src 'self' data: https:; " +
            "font-src 'self' https://fonts.gstatic.com; " +
            // Stripe mounts a cross-origin iframe for the card element
            "frame-src https://js.stripe.com https://hooks.stripe.com; " +
            // Stripe.js fetches confirmations from api.stripe.com
            "connect-src 'self' https://api.stripe.com; " +
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
                // Stripe webhook: server-to-server call — Stripe can't send a CSRF token.
                // Security is provided instead by HMAC signature verification in the controller.
                .ignoringRequestMatchers("/stripe/webhook")
            )

            // ── Authorization rules ──────────────────────────────────────────
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/", "/shop/**", "/product/**",
                    "/register", "/login",
                    "/about", "/about/**",
                    "/privacy-policy", "/terms-of-service", "/refund-and-shipping",
                    "/sitemap.xml", "/robots.txt",
                    "/og-image.svg", "/og-image.png",
                    "/subscribe", "/unsubscribe",
                    "/forgot-password", "/reset-password",
                    "/css/**", "/js/**", "/img/**", "/images/**", "/manifest.webmanifest",
                    "/stripe/webhook",
                    "/webjars/**", "/error",
                    "/actuator/health"
                ).permitAll()
                // Discount validation — authenticated users only (already covered by anyRequest but explicit here)
                .requestMatchers("/api/discount/validate").authenticated()
                // Cart API — guests may add items; login is required only at checkout
                .requestMatchers("/api/cart/**").permitAll()
                // /cart is public so guests can review their cart before login.
                // Auth gate stays at /checkout — that is where payment intent requires identity.
                .requestMatchers("/cart").permitAll()
                .requestMatchers("/account/**", "/checkout/**", "/orders/**", "/order/**").authenticated()
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
     * Sets SameSite=Lax on the JSESSIONID cookie at the Tomcat level.
     *
     * <p><strong>Why Lax and not Strict:</strong> Strict omits the cookie on ALL
     * top-level navigations from external origins. Two flows this app depends on
     * break under Strict:
     * <ol>
     *   <li>Stripe redirect-based payment methods — {@code automatic_payment_methods}
     *       is enabled, so methods like Cash App Pay and some 3DS bank flows return
     *       the customer via a top-level redirect from stripe.com. Under Strict the
     *       session cookie is not sent on that return: the customer lands logged out,
     *       {@code pendingOrderId} is unreachable, and the order is orphaned.</li>
     *   <li>Links in transactional emails ("VIEW MY ORDER") — clicking from Gmail is
     *       a cross-site top-level navigation; Strict makes every email link appear
     *       logged out.</li>
     * </ol>
     * Lax still withholds the cookie on cross-site subresource requests and
     * cross-site POSTs, and CSRF tokens cover state-changing requests.
     *
     * <p>Note: also set {@code server.servlet.session.cookie.secure=true} in
     * {@code application-prod.properties} so the Secure flag is only active
     * in production (HTTPS), preventing the dev server from breaking on HTTP.
     */
    @Bean
    public TomcatContextCustomizer sameSiteCookieCustomizer() {
        return (Context context) -> {
            Rfc6265CookieProcessor cookieProcessor = new Rfc6265CookieProcessor();
            cookieProcessor.setSameSiteCookies(SameSiteCookies.LAX.getValue());
            context.setCookieProcessor(cookieProcessor);
        };
    }
}
