package com.akumathreads.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Handles failed login attempts with brute-force protection.
 *
 * <p>Lock-check ordering contract (per spec):
 * <ol>
 *   <li>Check BEFORE incrementing — so the 5th bad attempt locks, not the 6th.</li>
 *   <li>Increment both username and client-IP counters.</li>
 *   <li>Re-check after increment to catch the transition exactly on attempt 5.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class CustomAuthenticationFailureHandler implements AuthenticationFailureHandler {

    private final LoginAttemptService loginAttemptService;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request,
                                        HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {

        String username = sanitize(request.getParameter("username"));
        String clientIp = resolveClientIp(request);

        // ── Step 1: Pre-increment check ──────────────────────────────────────
        // If either key is already at or beyond the threshold, refuse immediately.
        if (loginAttemptService.isBlocked(username) || loginAttemptService.isBlocked(clientIp)) {
            response.sendRedirect(request.getContextPath() + "/login?locked=true");
            return;
        }

        // ── Step 2: Register this failure ────────────────────────────────────
        loginAttemptService.registerFailure(username);
        loginAttemptService.registerFailure(clientIp);

        // ── Step 3: Post-increment check ─────────────────────────────────────
        // Catches the exact transition: e.g. counter just went 4 → 5.
        if (loginAttemptService.isBlocked(username) || loginAttemptService.isBlocked(clientIp)) {
            response.sendRedirect(request.getContextPath() + "/login?locked=true");
            return;
        }

        // ── Step 4: Inform the user of remaining attempts ────────────────────
        int remaining = loginAttemptService.remainingAttempts(username);
        response.sendRedirect(
                request.getContextPath() + "/login?error=true&remaining=" + remaining);
    }

    /**
     * Resolves the real client IP, respecting reverse-proxy {@code X-Forwarded-For} headers.
     * Only trusts the first (client-set) entry to prevent header spoofing.
     */
    private String resolveClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * Normalises the submitted username: lowercase trim, capped at 255 chars
     * to prevent DoS via arbitrarily long cache keys.
     */
    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.strip().toLowerCase();
        return trimmed.length() > 255 ? trimmed.substring(0, 255) : trimmed;
    }
}
