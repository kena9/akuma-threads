package com.akumathreads.controller;

import com.akumathreads.service.EmailService;
import com.akumathreads.service.UserService;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Handles the forgot-password / reset-password flow.
 *
 * <p>POST /forgot-password → creates token, emails link (always shows the same success message
 * regardless of whether the account exists, to prevent user enumeration).
 *
 * <p>Rate-limited to 5 requests per email address per hour (P2-9 fix) using the
 * {@code forgotPasswordCache} Caffeine bean. Exceeding the limit returns the same neutral
 * message to avoid leaking rate-limit information to an attacker.
 *
 * <p>GET  /reset-password?token=…  → shows the new-password form.
 * <p>POST /reset-password          → validates token, hashes + saves new password.
 */
@Controller
public class PasswordResetController {

    private static final int MAX_REQUESTS_PER_HOUR = 5;

    private final UserService              userService;
    private final EmailService             emailService;
    private final Cache<String, Integer>   forgotPasswordCache;

    public PasswordResetController(UserService userService,
                                   EmailService emailService,
                                   @Qualifier("forgotPasswordCache")
                                   Cache<String, Integer> forgotPasswordCache) {
        this.userService         = userService;
        this.emailService        = emailService;
        this.forgotPasswordCache = forgotPasswordCache;
    }

    // ── Forgot password ───────────────────────────────────────────────────────

    @GetMapping("/forgot-password")
    public String forgotPasswordPage() {
        return "auth/forgot-password";
    }

    @PostMapping("/forgot-password")
    public String sendResetLink(@RequestParam String email,
                                RedirectAttributes ra) {

        // Rate limit: 5 requests per email per hour (P2-9 fix)
        String key   = email.toLowerCase().strip();
        int    count = forgotPasswordCache.asMap()
                .merge(key, 1, Integer::sum);

        if (count > MAX_REQUESTS_PER_HOUR) {
            // Same neutral message — don't reveal that they were rate-limited
            ra.addFlashAttribute("successMsg",
                    "If an account with that email exists, you'll receive a reset link shortly.");
            return "redirect:/forgot-password";
        }

        // Create token (null = email not found — we don't tell the user which)
        String token = userService.createPasswordResetToken(email);
        if (token != null) {
            emailService.sendPasswordResetEmail(email, token);
        }

        // Always show the same message to prevent user enumeration
        ra.addFlashAttribute("successMsg",
                "If an account with that email exists, you'll receive a reset link shortly.");
        return "redirect:/forgot-password";
    }

    // ── Reset password ────────────────────────────────────────────────────────

    @GetMapping("/reset-password")
    public String resetPasswordPage(@RequestParam String token, Model model) {
        boolean valid = userService.validateResetToken(token).isPresent();
        model.addAttribute("token", token);
        model.addAttribute("valid", valid);
        return "auth/reset-password";
    }

    @PostMapping("/reset-password")
    public String doReset(@RequestParam String token,
                          @RequestParam String password,
                          @RequestParam String confirmPassword,
                          RedirectAttributes ra) {

        if (!password.equals(confirmPassword)) {
            ra.addFlashAttribute("errorMsg", "Passwords do not match.");
            return "redirect:/reset-password?token=" + token;
        }

        if (password.length() < 8) {
            ra.addFlashAttribute("errorMsg", "Password must be at least 8 characters.");
            return "redirect:/reset-password?token=" + token;
        }

        boolean ok = userService.resetPassword(token, password);
        if (!ok) {
            ra.addFlashAttribute("errorMsg",
                    "This reset link is invalid or has expired. Please request a new one.");
            return "redirect:/forgot-password";
        }

        ra.addFlashAttribute("successMsg",
                "Password updated! You can now log in with your new password.");
        return "redirect:/login";
    }
}
